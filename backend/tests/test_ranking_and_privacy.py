from datetime import UTC, datetime

from app.agent import is_late_night
from app.protocol import FareObservation
from app.ranking import RankRequest, rank, savings
from app.state import Option


def opt(i, app, rt, lo, hi, eta, conf=0.9, rnd=1):
    return Option(
        option_id=f"opt_{i}",
        round=rnd,
        observation=FareObservation(
            app=app,
            ride_type=rt,
            fare_min=lo,
            fare_max=hi,
            eta_minutes=eta,
            captured_at=datetime(2026, 11, 2, tzinfo=UTC),
            confidence=conf,
            screen_signature="t.v1",
        ),
    )


OPTS = [
    opt(1, "uber", "cab", 300, 320, 3),
    opt(2, "ola", "cab", 250, 260, 12),
    opt(3, "rapido", "bike", 90, 100, 4),
    opt(4, "rapido", "auto", 150, 160, 6),
]


def test_cheapest_and_fastest():
    assert rank(OPTS, RankRequest(preference="cheapest"))[0].option.option_id == "opt_3"
    assert rank(OPTS, RankRequest(preference="fastest"))[0].option.option_id == "opt_1"


def test_late_night_penalises_bike():
    top = rank(OPTS, RankRequest(preference="cheapest", late_night=True))[0]
    assert top.option.observation.ride_type != "bike"


def test_low_confidence_excluded():
    r = rank([opt(1, "uber", "cab", 100, 100, 3, conf=0.2)], RankRequest())
    assert r[0].excluded_reason


def test_savings_same_type_same_round_only():
    chosen = OPTS[1]
    s = savings(chosen, OPTS + [opt(9, "uber", "cab", 900, 900, 1, rnd=0)])
    assert s.amount == 55 and s.compared_against.option_id == "opt_1"
    assert savings(OPTS[2], OPTS) is None  # only bike: nothing comparable


def test_late_night_window():
    assert is_late_night(datetime(2026, 11, 2, 17, 0, tzinfo=UTC))  # 22:30 IST
    assert not is_late_night(datetime(2026, 11, 2, 8, 0, tzinfo=UTC))  # 13:30 IST


def test_rate_limit(make_client, clock):
    from tests.conftest import Phone

    phone = Phone(make_client(fare_checks_per_hour=2), clock)
    _, apps = phone.answer_fare_checks(phone.say("Powai to Andheri station", category="auto"))
    assert len(apps) == 2


def test_auth_required(client):
    r = client.post("/v1/sessions/abcdefgh12/messages", json={"type": "user_message", "text": "hi"})
    assert r.status_code == 401


def test_delete_my_data(phone, client):
    phone.answer_fare_checks(phone.say("Powai to Andheri station", category="auto"))
    store = client.app.state.store
    r = client.delete("/v1/me", headers={"Authorization": f"Bearer {phone.token}"})
    assert r.status_code == 204
    from sqlalchemy import text

    with store.engine.connect() as c:
        for t in ("devices", "sessions", "fare_observations", "fare_check_events"):
            assert c.execute(text(f"select count(*) from {t}")).scalar() == 0  # noqa: S608
    assert (
        client.delete("/v1/me", headers={"Authorization": f"Bearer {phone.token}"}).status_code
        == 401
    )


def test_observation_storage_has_no_location_or_text(phone, client):
    phone.answer_fare_checks(phone.say("Powai to Andheri station", category="auto"))
    from sqlalchemy import inspect

    cols = {
        c["name"] for c in inspect(client.app.state.store.engine).get_columns("fare_observations")
    }
    assert not cols & {"lat", "lng", "text", "raw_text", "pickup", "drop"}


def test_readers_config(client):
    cfg = client.get("/v1/config/readers").json()
    assert set(cfg["apps"]) == {"uber", "ola", "rapido"}
