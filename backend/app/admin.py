"""Founder-only ride summaries: what each app returned, what was picked, what was saved.

Built only from stored, whitelisted fields (rule 3). Never places, message text, device ids
or tokens.
"""

import json
from typing import Any

from app.ranking import comparable_class, savings
from app.state import SessionState
from app.store import SessionRow


def ride_summary(row: SessionRow) -> dict[str, Any]:
    st = SessionState.model_validate(row.state)
    opts = list(st.options.values())
    chosen = st.options.get(st.chosen_option_id) if st.chosen_option_id else None
    sv = savings(chosen, opts) if chosen else None
    return {
        "session": row.id[:8],
        "updated_at": row.updated_at.isoformat(),
        "status": row.status,
        "category": st.ride_category.value if st.ride_category else None,
        "options": [
            {
                "id": o.option_id,
                "round": o.round,
                "app": o.observation.app.value,
                "product": o.observation.product,
                "ride_type": o.observation.ride_type.value,
                "comparable_class": comparable_class(o),
                "fare_min": o.observation.fare_min,
                "fare_max": o.observation.fare_max,
                "eta_minutes": o.observation.eta_minutes,
                "screen_signature": o.observation.screen_signature,
            }
            for o in sorted(opts, key=lambda o: (o.round, o.observation.fare_mid))
        ],
        "checks": _check_statuses(st),
        "recommended": st.recommended_option_id,
        "chosen": st.chosen_option_id,
        "saved": sv.amount if sv else None,
        "compared_against": sv.compared_against.model_dump(mode="json") if sv else None,
    }


def _check_statuses(st: SessionState) -> list[dict[str, str]]:
    """Per-app result of each fare check ("ok", "failed", "app_not_installed", ...), oldest
    first, from the tool results kept in the session. Statuses only, nothing else."""
    out: list[dict[str, str]] = []
    for m in st.messages:
        if m.get("role") != "tool":
            continue
        try:
            apps = json.loads(m.get("content") or "{}").get("apps")
        except (ValueError, AttributeError):
            continue
        if isinstance(apps, dict):
            out.append({str(k): str(v)[:32] for k, v in apps.items()})
    if st.batch is not None and st.batch.results:
        out.append(dict(st.batch.results))
    return out
