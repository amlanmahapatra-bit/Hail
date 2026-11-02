"""Records real backend responses for the Android UI screenshot test.

Runs a full conversation through the real backend (in-process) with the founder's recorded
Bangalore fares, and saves each response the phone would receive to
android/app/src/test/resources/ui/. Usage (from backend/):

  uv run python ../tools/make_ui_fixtures.py                   # offline fake LLM
  uv run python ../tools/make_ui_fixtures.py --llm openrouter  # real Gemini wording
"""

import argparse
import json
import sys
import tempfile
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
sys.path.insert(0, str(ROOT / "tools"))

from fastapi.testclient import TestClient  # noqa: E402

import fake_phone  # noqa: E402
from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402

OUT = ROOT / "android/app/src/test/resources/ui"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--llm", choices=["fake", "openrouter"], default="fake")
    ap.add_argument("--text", default="Koramangala jaana hai sasta wala")
    ap.add_argument("--category", default="auto")
    args = ap.parse_args()
    settings = Settings(hail_llm=args.llm, database_url=f"sqlite:///{tempfile.mkdtemp()}/ui.db")
    c = TestClient(create_app(settings))
    tok = c.post("/v1/devices", json={"platform": "android", "capabilities": {
        "can_check_fares": True, "can_book": True}}).json()["token"]
    h = {"Authorization": f"Bearer {tok}"}
    sid = uuid.uuid4().hex
    here = {"lat": 12.8875, "lng": 77.5970, "label": "Current location"}

    def post(body):
        return c.post(f"/v1/sessions/{sid}/messages", json=body, headers=h).json()

    ask = post({"type": "user_message", "text": args.text, "input_mode": "text", "location": here})
    assert ask.get("ask"), ask  # no ride type said: Hail asks
    out = post({"type": "user_message", "text": args.category.replace("_", " ").title(),
                "ride_category": args.category, "location": here})
    apps = []
    while out["type"] == "action_request":
        a = out["action"]
        apps.append(a["app"])
        out = post({"type": "tool_result", "action_id": out["action_id"], "status": "ok",
                    "observations": fake_phone.observations(a["app"], a["ride_types"])})
    assert out["type"] == "recommendation", out
    rec = out
    pick = next(o for o in rec["options"] if o["option_id"] == rec["recommended_option_id"])
    handoff = post({"type": "confirmation", "option_id": pick["option_id"],
                    "token": rec["confirmation"]["token"], "accept": True})
    done = post({"type": "tool_result", "action_id": handoff["action_id"], "status": "ok",
                 "handoff": {"opened": True}})
    OUT.mkdir(parents=True, exist_ok=True)
    rec["confirmation"]["token"] = "screenshot-fixture-token"  # never ship a real token
    (OUT / "conversation.json").write_text(json.dumps({
        "said": args.text, "ask": ask, "category": args.category, "apps_checked": apps,
        "recommendation": rec,
        "picked_option_id": pick["option_id"], "done": done,
    }, ensure_ascii=False, indent=1))
    print(f"wrote {OUT / 'conversation.json'}: {len(rec['options'])} options; "
          f"text={rec['text']!r}; done={done['text']!r}")


if __name__ == "__main__":
    main()
