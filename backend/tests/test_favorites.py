"""Favourite places: saved per device, one tap to use, removable, gone with delete-my-data."""

from tests.conftest import Phone


def _h(p):
    return {"Authorization": f"Bearer {p.token}"}


def test_add_list_remove(phone, client):
    r = client.post(
        "/v1/favorites",
        json={"label": "FF21 Maya Hostel", "lat": 12.8875, "lng": 77.597},
        headers=_h(phone),
    )
    assert r.status_code == 201
    fav = r.json()
    # same label again: no duplicate
    again = client.post("/v1/favorites", json={"label": "ff21 maya hostel"}, headers=_h(phone))
    assert again.json()["id"] == fav["id"]
    client.post("/v1/favorites", json={"label": "Office"}, headers=_h(phone))
    listed = client.get("/v1/favorites", headers=_h(phone)).json()["favorites"]
    assert [f["label"] for f in listed] == ["FF21 Maya Hostel", "Office"]
    assert client.delete(f"/v1/favorites/{fav['id']}", headers=_h(phone)).status_code == 204
    assert [
        f["label"] for f in client.get("/v1/favorites", headers=_h(phone)).json()["favorites"]
    ] == ["Office"]


def test_favourites_are_private_to_the_device(phone, client, clock):
    other = Phone(client, clock)
    fav = client.post("/v1/favorites", json={"label": "Home"}, headers=_h(phone)).json()
    assert client.get("/v1/favorites", headers=_h(other)).json()["favorites"] == []
    assert client.delete(f"/v1/favorites/{fav['id']}", headers=_h(other)).status_code == 404
    assert client.get("/v1/favorites").status_code == 401


def test_limits_and_validation(phone, client):
    assert (
        client.post(
            "/v1/favorites", json={"label": "x", "lat": 12.0}, headers=_h(phone)
        ).status_code
        == 422
    )
    assert client.post("/v1/favorites", json={"label": ""}, headers=_h(phone)).status_code == 422
    for i in range(20):
        assert (
            client.post(
                "/v1/favorites", json={"label": f"Place {i}"}, headers=_h(phone)
            ).status_code
            == 201
        )
    assert (
        client.post("/v1/favorites", json={"label": "One too many"}, headers=_h(phone)).status_code
        == 409
    )


def test_tapping_a_favourite_uses_its_saved_place(phone):
    fav = {"lat": 12.9352, "lng": 77.6245, "label": "Forum Mall Koramangala"}
    here = {"lat": 12.8875, "lng": 77.5970, "label": "Current location"}
    resp = phone.post(
        {
            "type": "user_message",
            "text": "to Forum Mall Koramangala",
            "destination": fav,
            "location": here,
            "ride_category": "auto",
        }
    )
    assert resp["type"] == "action_request"
    assert resp["action"]["drop"] == fav  # saved coordinates, no lookup


def test_delete_my_data_removes_favourites(phone, client):
    client.post("/v1/favorites", json={"label": "Home"}, headers=_h(phone))
    assert client.delete("/v1/me", headers=_h(phone)).status_code == 204
    from sqlalchemy import text

    with client.app.state.store.engine.connect() as c:
        assert c.execute(text("select count(*) from favorites")).scalar() == 0


def test_card_carries_the_destination_for_the_heart(phone):
    rec, _ = phone.answer_fare_checks(phone.say("Powai to Andheri station", category="auto"))
    assert rec["drop"]["label"] == "Andheri Station"
