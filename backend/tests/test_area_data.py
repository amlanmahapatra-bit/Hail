"""Opt-in area-level price data: off by default, neighbourhood cells only, deletable."""

from sqlalchemy import select

from app.areas import area_cell
from app.store import AreaFareRow, Store
from tests.conftest import Phone


def _rows(settings):
    store = Store(settings.database_url)
    with store.db() as db:
        return list(db.scalars(select(AreaFareRow)))


def _auth(phone):
    return {"Authorization": f"Bearer {phone.token}"}


def _ride(phone):
    _, apps = phone.answer_fare_checks(phone.say("Powai to Andheri station", category="auto"))
    return apps


def test_area_cell_is_neighbourhood_level():
    cell = area_cell(19.1176, 72.9060)  # Powai
    assert len(cell) == 6 and cell == area_cell(19.1180, 72.9064)  # ~50 m away: same cell


def test_nothing_is_stored_without_opt_in(make_client, clock, settings):
    phone = Phone(make_client(), clock)
    assert phone.c.get("/v1/me/area-data", headers=_auth(phone)).json() == {"opt_in": False}
    _ride(phone)
    assert _rows(settings) == []


def test_opt_in_stores_cells_not_places_and_opt_out_deletes(make_client, clock, settings):
    phone = Phone(make_client(), clock)
    r = phone.c.put("/v1/me/area-data", json={"opt_in": True}, headers=_auth(phone))
    assert r.status_code == 200 and r.json() == {"opt_in": True}
    _ride(phone)
    rows = _rows(settings)
    assert rows and all(len(x.pickup_area) == 6 and len(x.drop_area) == 6 for x in rows)
    assert not hasattr(rows[0], "lat") and not hasattr(rows[0], "label")
    phone.c.put("/v1/me/area-data", json={"opt_in": False}, headers=_auth(phone))
    assert _rows(settings) == []


def test_delete_my_data_removes_area_data(make_client, clock, settings):
    phone = Phone(make_client(), clock)
    phone.c.put("/v1/me/area-data", json={"opt_in": True}, headers=_auth(phone))
    _ride(phone)
    assert _rows(settings)
    assert phone.c.delete("/v1/me", headers=_auth(phone)).status_code == 204
    assert _rows(settings) == []
