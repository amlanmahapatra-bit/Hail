"""Deterministic ranking and savings. No LLM involved.

Fares arrive as ranges, so ranking and savings both use the midpoint (fare_mid).
"""

from typing import Literal

from pydantic import BaseModel

from app.catalog import lookup
from app.protocol import OptionView, RideType, Savings
from app.state import Option

Preference = Literal["cheapest", "fastest", "balanced"]

UNKNOWN_ETA = 30  # minutes assumed when a reader could not read the ETA


class RankRequest(BaseModel):
    preference: Preference = "balanced"
    ride_type: RideType | None = None
    luggage: bool = False
    late_night: bool = False


class Ranked(BaseModel):
    option: Option
    score: float  # lower is better
    excluded_reason: str | None = None


def view(o: Option) -> OptionView:
    ob = o.observation
    return OptionView(
        option_id=o.option_id,
        app=ob.app,
        ride_type=ob.ride_type,
        product_name=product.name if (product := lookup(ob.app, ob.product)) else None,
        fare_min=ob.fare_min,
        fare_max=ob.fare_max,
        eta_minutes=ob.eta_minutes,
        surge_flag=ob.surge_flag,
    )


def _exclusion(o: Option, req: RankRequest) -> str | None:
    rt = o.observation.ride_type
    if req.ride_type and rt != req.ride_type:
        return f"user asked for {req.ride_type.value}"
    if req.luggage and rt == RideType.BIKE:
        return "luggage does not fit on a bike"
    if req.late_night and rt == RideType.BIKE and req.ride_type != RideType.BIKE:
        return "late night: bikes only if the user asks for one"
    if o.observation.confidence < 0.5:
        return "fare read with low confidence"
    return None


# Late-night safety weight for autos (bikes are excluded unless explicitly requested).
_NIGHT_FARE_FACTOR = {RideType.CAB: 1.0, RideType.AUTO: 1.2, RideType.BIKE: 1.0}
_NIGHT_EXTRA_MIN = {RideType.CAB: 0, RideType.AUTO: 4, RideType.BIKE: 0}


def rank(options: list[Option], req: RankRequest) -> list[Ranked]:
    """Return all options: eligible ones best-first, then excluded ones with a reason.

    cheapest: lowest fare, ties broken by ETA (strict: the user asked for cheapest)
    fastest:  lowest ETA, ties broken by fare
    balanced: 0.5 * normalised fare + 0.5 * normalised ETA
    Surge adds 5% to the effective fare (surge fares tend to move by booking time).
    Late night raises the safety weight: bikes are excluded unless explicitly requested,
    autos count as 20% dearer and 4 minutes slower, so cabs win close calls.
    Unknown ETA counts as UNKNOWN_ETA minutes.
    """
    eligible, excluded = [], []
    for o in options:
        reason = _exclusion(o, req)
        if reason:
            excluded.append(Ranked(option=o, score=float("inf"), excluded_reason=reason))
        else:
            eligible.append(o)
    if not eligible:
        return excluded

    def eff_fare(o: Option) -> float:
        f = o.observation.fare_mid * (1.05 if o.observation.surge_flag else 1.0)
        return f * (_NIGHT_FARE_FACTOR[o.observation.ride_type] if req.late_night else 1.0)

    def eff_eta(o: Option) -> float:
        e = o.observation.eta_minutes
        e = UNKNOWN_ETA if e is None else e
        return e + (_NIGHT_EXTRA_MIN[o.observation.ride_type] if req.late_night else 0)

    fares = [eff_fare(o) for o in eligible]
    etas = [eff_eta(o) for o in eligible]

    def norm(v: float, vs: list[float]) -> float:
        lo, hi = min(vs), max(vs)
        return 0.0 if hi == lo else (v - lo) / (hi - lo)

    scored = []
    for o, f, e in zip(eligible, fares, etas, strict=True):
        if req.preference == "cheapest":
            key = (f, e)
        elif req.preference == "fastest":
            key = (e, f)
        else:
            key = (0.5 * norm(f, fares) + 0.5 * norm(e, etas), f)
        scored.append((key, Ranked(option=o, score=round(key[0], 4))))
    scored.sort(key=lambda kr: kr[0])
    return [r for _, r in scored] + excluded


def comparable_class(o: Option) -> str:
    p = lookup(o.observation.app, o.observation.product)
    if p and p.comparable_class:
        return p.comparable_class
    return o.observation.ride_type.value


def savings(chosen: Option, checked: list[Option]) -> Savings | None:
    """Savings definition (product decision, see docs/decisions.md):

        saved = highest fare among COMPARABLE options checked - fare of the chosen option

    "Comparable" means the same comparable class from the catalog (e.g. economy AC cab vs
    economy AC cab, sedan vs sedan, auto vs auto; never Uber Black vs Ola Mini Non AC), from
    another app, in the same fare-check round. Without a catalog product it falls back to the
    same ride type. Fares are midpoints of the ranges shown (exact prices: min == max).
    Returns None when there is nothing comparable or the saving rounds to zero, so the client
    shows no savings line at all.
    """
    cls = comparable_class(chosen)
    comparable = [
        o
        for o in checked
        if o.round == chosen.round
        and o.observation.app != chosen.observation.app
        and comparable_class(o) == cls
    ]
    if not comparable:
        return None
    priciest = max(comparable, key=lambda o: o.observation.fare_mid)
    amount = round(priciest.observation.fare_mid - chosen.observation.fare_mid)
    if amount <= 0:
        return None
    return Savings(amount=amount, compared_against=view(priciest))
