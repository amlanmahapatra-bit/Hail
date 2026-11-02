"""Scripted fake phone: drives the Hail backend without an Android device.

It registers as an Android device, sends a ride request, answers every CHECK_FARES with
canned observations, shows the options card, checks that a forged tap is refused, then
"taps" an option and acknowledges the OPEN_APP handoff. Hail never books.

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

RECORDED = Path(__file__).with_name("recorded_fares_blr_2026-11-02.json")
# Real fares transcribed from the founder's recordings (Bangalore, 2026-11-02). See the file.
FARES = json.loads(RECORDED.read_text(encoding="utf-8"))["fares"]


def observations(app: str, ride_types: list[str]) -> list[dict[str, Any]]:
    now = datetime.now(UTC).isoformat()
    return [
        {"app": app, "ride_type": f["ride_type"], "product": f["product"],
         "fare_min": f["fare"], "fare_max": f["fare"], "currency": "INR",
         "eta_minutes": f["eta"], "surge_flag": False, "captured_at": now, "confidence": 0.9,
         "screen_signature": f"{app}.recorded.v1"}
        for f in FARES[app]
        if f["ride_type"] in ride_types
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
    ap.add_argument("--pick", help="option_id to tap instead of the highlighted one")
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

    print("OPTIONS (best first):")
    for o in out["options"]:
        star = "*" if o["option_id"] == out["recommended_option_id"] else " "
        saves = f"  saves Rs{o['saves']}" if o.get("saves") else ""
        name = o.get("product_name") or o["ride_type"]
        print(f"  {star} {o['option_id']:6} {o['app']:7} {name:14} "
              f"Rs{o['fare_min']:.2f}  {o['eta_minutes']} min{saves}")
    print()
    choice = args.pick or out["recommended_option_id"]

    # The gate: no handoff without the real token from the card.
    forged = send({"type": "confirmation", "option_id": choice,
                   "token": "forged-token-forged-token"})
    if forged["type"] == "handoff_request":
        failures.append("OPEN_APP was emitted with a forged token")

    handoff = send({"type": "confirmation", "option_id": choice,
                    "token": out["confirmation"]["token"], "accept": True})
    if handoff["type"] != "handoff_request":
        failures.append(f"expected handoff_request after the tap, got {handoff['type']}")
    else:
        done = send({"type": "tool_result", "action_id": handoff["action_id"], "status": "ok",
                     "handoff": {"opened": True}})
        print(f"FINAL: {done.get('text')}")

    if failures:
        print("FAIL:", *failures, sep="\n  ")
        return 1
    print("PASS: 3 fare checks -> options card -> forged tap refused -> user's pick opened")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
