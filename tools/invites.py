"""Manage beta invite codes (run on the server, from backend/).

  uv run python ../tools/invites.py create 10 --note "Mesa batch"   # 10 one-use codes
  uv run python ../tools/invites.py create 1 --uses 50 --note "hostel group"
  uv run python ../tools/invites.py list                            # codes and how often used
  uv run python ../tools/invites.py requests                        # pending "request an invite"
  uv run python ../tools/invites.py approve 3                       # code for request #3

Uses DATABASE_URL from backend/.env, the same database as the server.
"""

import argparse
import sys
from datetime import UTC, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))

from app.config import get_settings  # noqa: E402
from app.store import Store  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("create")
    c.add_argument("count", type=int)
    c.add_argument("--uses", type=int, default=1)
    c.add_argument("--note", default="")
    sub.add_parser("list")
    sub.add_parser("requests")
    a = sub.add_parser("approve")
    a.add_argument("request_id", type=int)
    args = ap.parse_args()

    store = Store(get_settings().database_url)
    now = datetime.now(UTC)
    if args.cmd == "create":
        for _ in range(args.count):
            print(store.create_invite(now, note=args.note, max_uses=args.uses))
    elif args.cmd == "list":
        for i in store.list_invites():
            print(f"{i.code}  used {i.uses}/{i.max_uses}  {i.note}")
    elif args.cmd == "requests":
        for r in store.list_invite_requests("pending"):
            print(f"#{r.id}  {r.created_at:%d %b %H:%M}  {r.contact}  {r.city or ''}")
    elif args.cmd == "approve":
        code = store.approve_invite_request(args.request_id, now)
        if code is None:
            print("no such request")
            return 1
        print(f"Send this code to the requester: {code}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
