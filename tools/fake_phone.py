"""Scripted fake phone: drives the Hail backend without an Android device.

It registers as an Android device, sends a ride request, answers every CHECK_FARES with
canned observations, checks that BOOK is refused without a valid confirmation, confirms
and reports the booking.

Usage (from the backend/ directory):
  uv run python ../tools/fake_phone.py                      # in-process, offline fake LLM
  uv run python ../tools/fake_phone.py --llm openrouter     # in-process, real Gemini
  uv run python ../tools/fake_phone.py --base-url http://127.0.0.1:8000   # running server

Exit code 0 means the acceptance flow passed.
"""

import argparse
import json
import sys
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

# Canned fares: app -> [(ride_type, fare_min, fare_max, eta_minutes)]. Made up for testing.
FARES = {
    "uber": [("cab", 310, 340, 6), ("auto", 180, 190, 4), ("bike", 95, 105, 3)],
    "ola": [("cab", 290, 320, 8), ("auto", 170, 185, 7)],
    "rapido": [("cab", 270, 300, 9), ("auto", 150, 160, 5), ("bike", 80, 90, 2)],
}


def observations(app: str, ride_types: list[str]) -> list[dict[str, Any]]:
    now = datetime.now(UTC).isoformat()
    return [
        {"app": app, "ride_type": rt, "fare_min": lo, "fare_max": hi, "currency": "INR",
         "eta_minutes": eta, "surge_flag": False, "captured_at": now, "confidence": 0.9,
         "screen_signature": f"{app}.fake.v1"}
        for rt, lo, hi, eta in FARES[app]
        if rt in ride_types
    ]


def make_transport(args: argparse.Namespace):
    if args.base_url:
        import httpx

        return httpx.Client(base_url=args.base_url, timeout=90)
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
    from fastapi.testclient import TestClient

    from app.config import Settings
    from app.main import create_app

    import tempfile

    db = Path(tempfile.mkdtemp(prefix="hail-fake-phone-")) / "hail.db"
    settings = Settings(hail_llm=args.llm, database_url=f"sqlite:///{db}")
    return TestClient(create_app(settings))


def show(direction: str, body: dict[str, Any]) -> None:
    shown = dict(body)
    if "confirmation" in shown and isinstance(shown["confirmation"], dict):
        shown["confirmation"] = shown["confirmation"] | {"token": "<redacted>"}
    if shown.get("type") == "confirmation":
        shown["token"] = "<redacted>"
    print(f"{direction} {json.dumps(shown, ensure_ascii=False)}\n")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--text", default="Powai to Andheri station, cheapest")
    ap.add_argument("--base-url", help="Talk to a running server instead of in-process.")
    ap.add_argument("--llm", choices=["fake", "openrouter"], default="fake",
                    help="In-process only. 'openrouter' needs OPENROUTER_API_KEY in .env.")
    args = ap.parse_args()

    http = make_transport(args)
    r = http.post("/v1/devices", json={"platform": "android",
                                       "capabilities": {"can_check_fares": True,
                                                        "can_book": True}})
    r.raise_for_status()
    headers = {"Authorization": f"Bearer {r.json()['token']}"}
    sid = uuid.uuid4().hex

    def send(body: dict[str, Any]) -> dict[str, Any]:
        show("PHONE  ->", body)
        resp = http.post(f"/v1/sessions/{sid}/messages", json=body, headers=headers)
        if resp.status_code != 200:
            print(f"HTTP {resp.status_code}: {resp.text}")
            raise SystemExit(1)
        out = resp.json()
        show("<- BACKEND", out)
        return out

    checked: list[str] = []
    out = send({"type": "user_message", "text": args.text})
    while out["type"] == "action_request":
        a = out["action"]
        checked.append(a["app"])
        out = send({"type": "tool_result", "action_id": out["action_id"], "status": "ok",
                    "observations": observations(a["app"], a["ride_types"])})

    failures = []
    if len(checked) != 3:
        failures.append(f"expected fare checks on 3 apps, got {checked}")
    if out["type"] != "recommendation":
        failures.append(f"expected a recommendation, got {out['type']}")
        print("FAIL:", *failures, sep="\n  ")
        return 1

    # The gate: BOOK must be refused without the real confirmation token.
    forged = send({"type": "confirmation", "option_id": out["confirmation"]["option_id"],
                   "token": "forged-token-forged-token"})
    if forged["type"] == "booking_request":
        failures.append("BOOK was emitted with a forged token")

    booking = send({"type": "confirmation", "option_id": out["confirmation"]["option_id"],
                    "token": out["confirmation"]["token"], "accept": True})
    if booking["type"] != "booking_request":
        failures.append(f"expected booking_request after confirmation, got {booking['type']}")
    else:
        done = send({"type": "tool_result", "action_id": booking["action_id"], "status": "ok",
                     "booking": {"booked": True}})
        print(f"FINAL: {done.get('text')}")

    if failures:
        print("FAIL:", *failures, sep="\n  ")
        return 1
    print("PASS: 3 fare checks -> recommendation -> forged confirmation refused -> BOOK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
