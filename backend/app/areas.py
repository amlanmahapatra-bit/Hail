"""Area cells for opt-in price data: a 6-character geohash, about 1.2 km x 0.6 km.

Neighbourhood level on purpose. A cell names an area, never a building or an address, and the
exact coordinates are never stored.
"""

_BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
PRECISION = 6


def area_cell(lat: float, lng: float, precision: int = PRECISION) -> str:
    lat_lo, lat_hi, lng_lo, lng_hi = -90.0, 90.0, -180.0, 180.0
    bits, bit, ch, even, out = 0, 0, 0, True, []
    while len(out) < precision:
        if even:
            mid = (lng_lo + lng_hi) / 2
            if lng > mid:
                ch, lng_lo = ch | (1 << (4 - bit)), mid
            else:
                lng_hi = mid
        else:
            mid = (lat_lo + lat_hi) / 2
            if lat > mid:
                ch, lat_lo = ch | (1 << (4 - bit)), mid
            else:
                lat_hi = mid
        even = not even
        bit += 1
        bits += 1
        if bit == 5:
            out.append(_BASE32[ch])
            bit, ch = 0, 0
    return "".join(out)
