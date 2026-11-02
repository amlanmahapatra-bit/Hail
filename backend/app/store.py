"""Persistence. SQLAlchemy 2.0 so SQLite -> Postgres is a DATABASE_URL change.

Tables:
  devices            one row per installed client; token stored as SHA-256 hash only
  sessions           session state as JSON, optimistic locking via `version`
  fare_observations  the whitelisted observation fields only (no screen text, no location)
  fare_check_events  timestamps for per-device rate limiting
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
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker

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


class Conflict(Exception):
    """Session was modified concurrently (e.g. a duplicate POST)."""


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


class Store:
    def __init__(self, database_url: str):
        kwargs: dict[str, Any] = {}
        if database_url.startswith("sqlite"):
            kwargs["connect_args"] = {"check_same_thread": False}
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
