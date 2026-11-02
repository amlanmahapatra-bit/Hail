import json
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.llm import FakeLLM
from app.main import create_app

# 2026-11-02 14:00 IST (daytime, so late-night weighting is off by default)
T0 = datetime(2026, 11, 2, 8, 30, tzinfo=UTC)

RECORDED = Path(__file__).parents[2] / "tools" / "recorded_fares_blr_2026-11-02.json"
# Real fares from the founder's recordings (Bangalore, 2026-11-02); see that file.
FARES = json.loads(RECORDED.read_text(encoding="utf-8"))["fares"]


class Clock:
    def __init__(self):
        self.now = T0

    def __call__(self):
        return self.now

    def advance(self, seconds: float):
        self.now += timedelta(seconds=seconds)


def observations(app: str, ride_types: list[str], when: datetime) -> list[dict]:
    return [
        {
            "app": app,
            "ride_type": f["ride_type"],
            "product": f["product"],
            "fare_min": f["fare"],
            "fare_max": f["fare"],
            "currency": "INR",
            "eta_minutes": f["eta"],
            "surge_flag": False,
            "captured_at": when.isoformat(),
            "confidence": 0.9,
            "screen_signature": f"{app}.recorded.v1",
        }
        for f in FARES[app]
        if f["ride_type"] in ride_types
    ]


class Phone:
    """Minimal test client: one device, one session."""

    def __init__(self, client: TestClient, clock: Clock, platform: str = "android"):
        self.c, self.clock = client, clock
        caps = {"can_check_fares": True, "can_book": True} if platform == "android" else {}
        r = client.post("/v1/devices", json={"platform": platform, "capabilities": caps})
        assert r.status_code == 201, r.text
        self.token = r.json()["token"]
        self.session = uuid.uuid4().hex

    def post(self, body: dict, session: str | None = None, expect: int = 200) -> dict:
        r = self.c.post(
            f"/v1/sessions/{session or self.session}/messages",
            json=body,
            headers={"Authorization": f"Bearer {self.token}"},
        )
        assert r.status_code == expect, r.text
        return r.json()

    def say(self, text: str, apps: list[str] | None = None, **kw) -> dict:
        body = {"type": "user_message", "text": text}
        if apps is not None:
            body["available_apps"] = apps
        return self.post(body, **kw)

    def answer_fare_checks(self, resp: dict) -> tuple[dict, list[str]]:
        apps = []
        while resp["type"] == "action_request":
            a = resp["action"]
            apps.append(a["app"])
            resp = self.post(
                {
                    "type": "tool_result",
                    "action_id": resp["action_id"],
                    "status": "ok",
                    "observations": observations(a["app"], a["ride_types"], self.clock()),
                }
            )
        return resp, apps

    def confirm(self, rec: dict, option_id: str | None = None, **over) -> dict:
        """Tap an option on the card (default: the highlighted one)."""
        body = {
            "type": "confirmation",
            "option_id": option_id or rec["recommended_option_id"],
            "token": rec["confirmation"]["token"],
            "accept": True,
        }
        return self.post(body | over)

    def opened(self, handoff: dict, ok: bool = True) -> dict:
        return self.post(
            {
                "type": "tool_result",
                "action_id": handoff["action_id"],
                "status": "ok" if ok else "failed",
                "handoff": {"opened": ok},
            }
        )


@pytest.fixture
def clock():
    return Clock()


@pytest.fixture
def settings(tmp_path):
    return Settings(
        database_url=f"sqlite:///{tmp_path}/test.db",
        hail_llm="fake",
        confirmation_ttl_seconds=90,
        fare_checks_per_hour=30,
    )


@pytest.fixture
def make_client(settings, clock):
    def make(llm=None, **overrides):
        s = settings.model_copy(update=overrides)
        return TestClient(create_app(s, llm=llm or FakeLLM(), clock=clock))

    return make


@pytest.fixture
def client(make_client):
    return make_client()


@pytest.fixture
def phone(client, clock):
    return Phone(client, clock)
