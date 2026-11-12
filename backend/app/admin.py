"""Founder-only ride summaries: what each app returned, what was picked, what was saved.

Built only from stored, whitelisted fields (rule 3). Never places, message text, device ids
or tokens.
"""

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
        "recommended": st.recommended_option_id,
        "chosen": st.chosen_option_id,
        "saved": sv.amount if sv else None,
        "compared_against": sv.compared_against.model_dump(mode="json") if sv else None,
    }
