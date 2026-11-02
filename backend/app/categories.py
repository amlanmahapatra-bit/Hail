"""Ride category from what the user typed or said ("rickshaw", "big cab", "XL", "gaadi").

Checked in code before any model call, so a stated category never costs a question. Kept in
step with the phone's matcher (VoicePick / category buttons).
"""

import re

from app.protocol import RideCategory

_PATTERNS: list[tuple[RideCategory, str]] = [
    # XL first: "big cab" must not read as plain "cab".
    (
        RideCategory.CAB_XL,
        r"\bxl\b|\bsuv\b|\bbig(?:ger)? (?:cab|car|gaadi)\b|\blarge (?:cab|car)\b"
        r"|\b(?:6|six)[ -]?seater\b|\binnova\b|\bxuv\b|\bcab ?xl\b|\bbadi gaadi\b",
    ),
    # Premium before plain cab: "sedan", "premium cab" are not economy.
    (RideCategory.PREMIUM, r"\bpremium\b|\bsedan\b|\bluxury\b|\bblack\b|\bprime\b|\bpremier\b"),
    (RideCategory.AUTO, r"\bauto\b|\brick?shaw\b|\briksha\b|\brikshaw\b|\btuk ?tuk\b"),
    (RideCategory.BIKE, r"\bbike\b|\bscooty\b|\bscooter\b|\btwo[ -]?wheeler\b|\bmoto\b"),
    (RideCategory.CAB, r"\bcab\b|\bcar\b|\btaxi\b|\bgaadi\b|\bgadi\b"),
]


def detect(text: str) -> RideCategory | None:
    t = text.lower()
    for cat, pat in _PATTERNS:
        if re.search(pat, t):
            return cat
    return None
