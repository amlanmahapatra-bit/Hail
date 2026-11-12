"""Persistence. SQLAlchemy 2.0 so SQLite -> Postgres is a DATABASE_URL change.

Tables:
  devices            one row per installed client; token stored as SHA-256 hash only
  sessions           session state as JSON, optimistic locking via `version`
  fare_observations  the whitelisted observation fields only (no screen text, no location)
  fare_check_events  timestamps for per-device rate limiting
  invites            beta invite codes and how often each was used
  invite_requests    people asking to join the beta (the contact they chose to give)
"""

import hashlib
import secrets
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import (
    JSON,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    create_engine,
    delete,
    func,
    select,
    update,
)
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker
from sqlalchemy.pool import NullPool

from app.protocol import Capabilities, FareObservation, Platform
from app.state import SessionState


class Base(DeclarativeBase):
    pass


class DeviceRow(Base):
    __tablename__ = "devices"
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    platform: Mapped[str] = mapped_column(String(16))
    capabilities: Mapped[dict[str, Any]] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class SessionRow(Base):
    __tablename__ = "sessions"
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)
    status: Mapped[str] = mapped_column(String(32))
    state: Mapped[dict[str, Any]] = mapped_column(JSON)
    version: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class FareObservationRow(Base):
    __tablename__ = "fare_observations"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(String(64), index=True)
    device_id: Mapped[str] = mapped_column(String(64), index=True)
    app: Mapped[str] = mapped_column(String(16))
    ride_type: Mapped[str] = mapped_column(String(16))
    fare_min: Mapped[float] = mapped_column(Float)
    fare_max: Mapped[float] = mapped_column(Float)
    currency: Mapped[str] = mapped_column(String(3))
    eta_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    surge_flag: Mapped[bool] = mapped_column(Boolean)
    captured_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    confidence: Mapped[float] = mapped_column(Float)
    screen_signature: Mapped[str] = mapped_column(String(64))
    product: Mapped[str | None] = mapped_column(String(40), nullable=True)  # catalog key


class FavoriteRow(Base):
    """A place the user hearted. Sensitive (home, office): per device, deleted with the device."""

    __tablename__ = "favorites"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(String(64), index=True)
    label: Mapped[str] = mapped_column(String(80))
    lat: Mapped[float | None] = mapped_column(Float, nullable=True)
    lng: Mapped[float | None] = mapped_column(Float, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class FareCheckEventRow(Base):
    __tablename__ = "fare_check_events"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(String(64), index=True)
    at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)


class InviteRow(Base):
    """A beta invite code. `uses` counts redemptions; a device keeps working once registered."""

    __tablename__ = "invites"
    code: Mapped[str] = mapped_column(String(32), primary_key=True)
    note: Mapped[str] = mapped_column(String(120), default="")
    max_uses: Mapped[int] = mapped_column(Integer, default=1)
    uses: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class InviteRequestRow(Base):
    """A request to join the beta. Holds only the contact and city the person typed in."""

    __tablename__ = "invite_requests"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    contact: Mapped[str] = mapped_column(String(120), index=True)
    city: Mapped[str | None] = mapped_column(String(60), nullable=True)
    status: Mapped[str] = mapped_column(String(16), default="pending")  # pending|invited
    invite_code: Mapped[str | None] = mapped_column(String(32), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class Conflict(Exception):
    """Session was modified concurrently (e.g. a duplicate POST)."""


def engine_url(database_url: str) -> str:
    """Use the psycopg 3 driver for plain postgres:// URLs (as Supabase gives them), keeping
    only the sslmode option: libpq rejects extras such as Supabase's `supa=` marker."""
    for prefix in ("postgres://", "postgresql://"):
        if database_url.startswith(prefix):
            base, _, query = database_url[len(prefix):].partition("?")
            keep = [kv for kv in query.split("&") if kv.startswith("sslmode=")]
            return "postgresql+psycopg://" + base + ("?" + "&".join(keep) if keep else "")
    return database_url


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


class Store:
    def __init__(self, database_url: str):
        database_url = engine_url(database_url)
        kwargs: dict[str, Any] = {}
        if database_url.startswith("sqlite"):
            kwargs["connect_args"] = {"check_same_thread": False}
        else:
            # Postgres (Supabase) from serverless functions: no pool kept between requests, and
            # no prepared statements, so it works through Supabase's transaction pooler.
            kwargs["poolclass"] = NullPool
            kwargs["connect_args"] = {"prepare_threshold": None}
        self.engine = create_engine(database_url, **kwargs)
        self._sm = sessionmaker(self.engine, expire_on_commit=False)
        Base.metadata.create_all(self.engine)

    def db(self) -> Session:
        return self._sm()

    # devices
    def register_device(self, platform: Platform, caps: Capabilities, now: datetime):
        device_id = "dev_" + secrets.token_hex(12)
        token = secrets.token_urlsafe(32)
        with self.db() as db, db.begin():
            db.add(
                DeviceRow(
                    id=device_id,
                    token_hash=_hash(token),
                    platform=platform.value,
                    capabilities=caps.model_dump(),
                    created_at=now,
                )
            )
        return device_id, token

    def device_by_token(self, token: str) -> DeviceRow | None:
        with self.db() as db:
            return db.scalar(select(DeviceRow).where(DeviceRow.token_hash == _hash(token)))

    # sessions
    def recent_sessions(self, limit: int) -> list[SessionRow]:
        with self.db() as db:
            q = select(SessionRow).order_by(SessionRow.updated_at.desc()).limit(limit)
            return list(db.scalars(q))

    def load_session(self, session_id: str) -> tuple[SessionRow | None, SessionState | None]:
        with self.db() as db:
            row = db.get(SessionRow, session_id)
            if row is None:
                return None, None
            return row, SessionState.model_validate(row.state)

    def create_session(self, session_id: str, device_id: str, now: datetime) -> SessionRow:
        st = SessionState()
        row = SessionRow(
            id=session_id,
            device_id=device_id,
            status=st.status.value,
            state=st.model_dump(mode="json"),
            version=0,
            created_at=now,
            updated_at=now,
        )
        with self.db() as db, db.begin():
            db.add(row)
        return row

    def save_session(self, row: SessionRow, state: SessionState, now: datetime) -> None:
        with self.db() as db, db.begin():
            res = db.execute(
                update(SessionRow)
                .where(SessionRow.id == row.id, SessionRow.version == row.version)
                .values(
                    state=state.model_dump(mode="json"),
                    status=state.status.value,
                    version=row.version + 1,
                    updated_at=now,
                )
            )
            if res.rowcount != 1:
                raise Conflict(row.id)

    # observations + rate limiting
    def record_observations(
        self, session_id: str, device_id: str, obs: list[FareObservation]
    ) -> None:
        with self.db() as db, db.begin():
            for o in obs:
                db.add(
                    FareObservationRow(
                        session_id=session_id,
                        device_id=device_id,
                        **o.model_dump(include=set(FareObservation.model_fields)),
                    )
                )

    def try_consume_fare_check(self, device_id: str, now: datetime, per_hour: int) -> bool:
        with self.db() as db, db.begin():
            n = db.scalar(
                select(func.count())
                .select_from(FareCheckEventRow)
                .where(
                    FareCheckEventRow.device_id == device_id,
                    FareCheckEventRow.at > now - timedelta(hours=1),
                )
            )
            if (n or 0) >= per_hour:
                return False
            db.add(FareCheckEventRow(device_id=device_id, at=now))
            return True

    # invites
    INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O/1/I: easy to read aloud

    def create_invite(self, now: datetime, note: str = "", max_uses: int = 1) -> str:
        code = "HAIL-" + "".join(secrets.choice(self.INVITE_ALPHABET) for _ in range(6))
        with self.db() as db, db.begin():
            db.add(InviteRow(code=code, note=note[:120], max_uses=max_uses, uses=0, created_at=now))
        return code

    def ensure_invites(self, codes: list[str], now: datetime, note: str = "seeded") -> None:
        """Adds each one-use code that doesn't exist yet. Used to seed a hosted database where
        the admin CLI can't reach it (SEED_INVITE_CODES). Safe to run on every start."""
        for raw in codes:
            code = raw.strip().upper()
            if not code:
                continue
            try:
                with self.db() as db, db.begin():
                    if db.get(InviteRow, code) is None:
                        db.add(InviteRow(code=code, note=note, max_uses=1, uses=0, created_at=now))
            except IntegrityError:
                pass  # another instance seeded it at the same moment

    def redeem_invite(self, code: str) -> bool:
        """Uses one redemption of `code` (case-insensitive). False if unknown or used up."""
        with self.db() as db, db.begin():
            res = db.execute(
                update(InviteRow)
                .where(InviteRow.code == code.strip().upper(), InviteRow.uses < InviteRow.max_uses)
                .values(uses=InviteRow.uses + 1)
            )
            return res.rowcount == 1

    def list_invites(self) -> list[InviteRow]:
        with self.db() as db:
            return list(db.scalars(select(InviteRow).order_by(InviteRow.created_at)))

    def add_invite_request(
        self, contact: str, city: str | None, now: datetime, per_day: int
    ) -> InviteRequestRow | None:
        """Stores a request (the same contact twice returns the first). None if the daily cap
        is reached."""
        contact = contact.strip()
        with self.db() as db, db.begin():
            existing = db.scalar(
                select(InviteRequestRow).where(
                    func.lower(InviteRequestRow.contact) == contact.lower()
                )
            )
            if existing is not None:
                return existing
            today = db.scalar(
                select(func.count())
                .select_from(InviteRequestRow)
                .where(InviteRequestRow.created_at >= now - timedelta(days=1))
            )
            if today >= per_day:
                return None
            row = InviteRequestRow(contact=contact, city=city, status="pending", created_at=now)
            db.add(row)
            db.flush()
            return row

    def list_invite_requests(self, status: str | None = None) -> list[InviteRequestRow]:
        with self.db() as db:
            q = select(InviteRequestRow).order_by(InviteRequestRow.created_at)
            if status:
                q = q.where(InviteRequestRow.status == status)
            return list(db.scalars(q))

    def approve_invite_request(self, request_id: int, now: datetime) -> str | None:
        """Creates a one-use code for a pending request and marks it invited."""
        with self.db() as db:
            row = db.get(InviteRequestRow, request_id)
            if row is None:
                return None
            if row.invite_code:
                return row.invite_code
        code = self.create_invite(now, note=f"request {request_id}")
        with self.db() as db, db.begin():
            db.execute(
                update(InviteRequestRow)
                .where(InviteRequestRow.id == request_id)
                .values(status="invited", invite_code=code)
            )
        return code

    # privacy
    def delete_device_data(self, device_id: str) -> None:
        with self.db() as db, db.begin():
            for model in (FareObservationRow, FareCheckEventRow, SessionRow, FavoriteRow):
                db.execute(delete(model).where(model.device_id == device_id))
            db.execute(delete(DeviceRow).where(DeviceRow.id == device_id))

    # favourites
    MAX_FAVORITES = 20

    def list_favorites(self, device_id: str) -> list[FavoriteRow]:
        with self.db() as db:
            return list(
                db.scalars(
                    select(FavoriteRow)
                    .where(FavoriteRow.device_id == device_id)
                    .order_by(FavoriteRow.created_at, FavoriteRow.id)
                )
            )

    def add_favorite(
        self, device_id: str, label: str, lat: float | None, lng: float | None, now: datetime
    ) -> FavoriteRow | None:
        """Adds (or returns the existing one with the same label). None if the list is full."""
        with self.db() as db, db.begin():
            rows = list(db.scalars(select(FavoriteRow).where(FavoriteRow.device_id == device_id)))
            for r in rows:
                if r.label.strip().lower() == label.strip().lower():
                    return r
            if len(rows) >= self.MAX_FAVORITES:
                return None
            row = FavoriteRow(
                device_id=device_id, label=label.strip(), lat=lat, lng=lng, created_at=now
            )
            db.add(row)
            db.flush()
            return row

    def delete_favorite(self, device_id: str, fav_id: int) -> bool:
        with self.db() as db, db.begin():
            res = db.execute(
                delete(FavoriteRow).where(
                    FavoriteRow.id == fav_id, FavoriteRow.device_id == device_id
                )
            )
            return res.rowcount == 1

    def purge_sessions_older_than(self, cutoff: datetime) -> int:
        """Retention: session state (incl. pickup/drop coordinates) is not kept long-term."""
        with self.db() as db, db.begin():
            return db.execute(delete(SessionRow).where(SessionRow.updated_at < cutoff)).rowcount
