"""Geocoding. WEEK-1 STUB: a small hand-made list of pilot-city places.

Coordinates are approximate landmark centres, good enough to test the loop; they are NOT
validated pickup points. Replace with a real geocoder (decision pending: cost + privacy)
behind the same function signature.
"""

import re

from pydantic import BaseModel

from app.protocol import Place


class GeoHit(BaseModel):
    city: str
    place: Place


_GAZETTEER: dict[str, dict[str, tuple[float, float, str]]] = {
    "mumbai": {
        "powai": (19.1176, 72.9060, "Powai"),
        "andheri station": (19.1197, 72.8464, "Andheri Station"),
        "andheri": (19.1197, 72.8464, "Andheri Station"),
        "bandra station": (19.0544, 72.8406, "Bandra Station"),
        "bandra": (19.0544, 72.8406, "Bandra Station"),
        "bkc": (19.0660, 72.8679, "Bandra Kurla Complex"),
        "dadar": (19.0186, 72.8424, "Dadar Station"),
        "cst": (18.9398, 72.8355, "CSMT"),
        "airport": (19.0974, 72.8745, "Mumbai Airport T2"),
        "lower parel": (18.9953, 72.8301, "Lower Parel"),
    },
    "bangalore": {
        "mg road": (12.9756, 77.6050, "MG Road"),
        "koramangala": (12.9352, 77.6245, "Koramangala"),
        "indiranagar": (12.9719, 77.6412, "Indiranagar"),
        "whitefield": (12.9698, 77.7500, "Whitefield"),
        "majestic": (12.9767, 77.5713, "Majestic"),
        "hsr layout": (12.9116, 77.6474, "HSR Layout"),
        "electronic city": (12.8452, 77.6602, "Electronic City"),
        "airport": (13.1986, 77.7066, "Kempegowda Airport"),
    },
}
_CITY_ALIASES = {"bengaluru": "bangalore", "blr": "bangalore", "bombay": "mumbai"}


# Rough city centres, used only to tell which pilot city the user is in.
_CITY_CENTRES = {"mumbai": (19.0760, 72.8777), "bangalore": (12.9716, 77.5946)}


def city_of(lat: float | None, lng: float | None, max_km: float = 60.0) -> str | None:
    """Pilot city the phone is in (by distance to the city centre), or None."""
    if lat is None or lng is None:
        return None
    from math import asin, cos, radians, sin, sqrt

    def km(a: tuple[float, float], b: tuple[float, float]) -> float:
        dlat, dlng = radians(b[0] - a[0]), radians(b[1] - a[1])
        h = sin(dlat / 2) ** 2 + cos(radians(a[0])) * cos(radians(b[0])) * sin(dlng / 2) ** 2
        return 6371 * 2 * asin(sqrt(h))

    best = min(_CITY_CENTRES, key=lambda c: km((lat, lng), _CITY_CENTRES[c]))
    return best if km((lat, lng), _CITY_CENTRES[best]) <= max_km else None


def normalize_city(city: str | None) -> str | None:
    if not city:
        return None
    c = city.strip().lower()
    return _CITY_ALIASES.get(c, c) if _CITY_ALIASES.get(c, c) in _GAZETTEER else None


def geocode(query: str, city: str | None) -> GeoHit | None:
    q = re.sub(r"\s+", " ", query.strip().lower())
    cities = [normalize_city(city)] if normalize_city(city) else list(_GAZETTEER)
    for c in cities:
        table = _GAZETTEER[c]
        if q in table:
            lat, lng, label = table[q]
            return GeoHit(city=c, place=Place(lat=lat, lng=lng, label=label))
    for c in cities:  # loose match: longest key contained in the query
        for key in sorted(_GAZETTEER[c], key=len, reverse=True):
            if key in q:
                lat, lng, label = _GAZETTEER[c][key]
                return GeoHit(city=c, place=Place(lat=lat, lng=lng, label=label))
    return None
