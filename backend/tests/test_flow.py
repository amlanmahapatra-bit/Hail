"""Acceptance: 'Powai to Andheri station, cheapest' end to end with the fake LLM."""


def test_full_loop_cheapest(phone):
    resp = phone.say("Powai to Andheri station, cheapest")
    assert resp["type"] == "action_request"
    a = resp["action"]
    assert a["kind"] == "CHECK_FARES"
    assert a["pickup"]["label"] == "Powai" and a["drop"]["label"] == "Andheri Station"
    assert isinstance(a["pickup"]["lat"], float)

    rec, apps = phone.answer_fare_checks(resp)
    assert sorted(apps) == ["ola", "rapido", "uber"]
    assert rec["type"] == "recommendation"
    # cheapest overall is Rapido bike (80-90)
    assert rec["recommended"]["app"] == "rapido" and rec["recommended"]["ride_type"] == "bike"
    assert rec["savings_if_booked"]["amount"] == 15  # vs Uber bike 100 midpoint
    assert len(rec["options"]) == 8

    booking = phone.confirm(rec)
    assert booking["type"] == "booking_request"
    assert booking["action"] == {
        "kind": "BOOK",
        "app": "rapido",
        "option_id": rec["recommended"]["option_id"],
        "ride_type": "bike",
        "pickup": a["pickup"],
        "drop": a["drop"],
    }

    done = phone.post(
        {
            "type": "tool_result",
            "action_id": booking["action_id"],
            "status": "ok",
            "booking": {"booked": True},
        }
    )
    assert done["type"] == "assistant_message" and done["done"] is True
    assert "You saved ₹15" in done["text"]


def test_luggage_excludes_bike_and_compares_same_type(phone):
    rec, _ = phone.answer_fare_checks(phone.say("Powai to Andheri station, cheapest, with luggage"))
    assert rec["recommended"]["ride_type"] == "auto"
    assert rec["recommended"]["app"] == "rapido"
    # vs priciest auto: Uber 185 midpoint - Rapido 155 midpoint
    assert rec["savings_if_booked"]["amount"] == 30


def test_ride_type_request_only_checks_that_type(phone):
    resp = phone.say("Powai to BKC by cab, cheapest")
    assert resp["action"]["ride_types"] == ["cab"]
    rec, _ = phone.answer_fare_checks(resp)
    assert {o["ride_type"] for o in rec["options"]} == {"cab"}


def test_unknown_place_asks_user(phone):
    resp = phone.say("Atlantis to Andheri station")
    assert resp["type"] == "assistant_message"
    assert "couldn't find" in resp["text"]


def test_stop_mid_check(phone):
    resp = phone.say("Powai to Andheri station")
    out = phone.post(
        {"type": "tool_result", "action_id": resp["action_id"], "status": "stopped_by_user"}
    )
    assert out["type"] == "assistant_message" and "Stopped" in out["text"]
    # session usable again
    assert phone.say("Powai to Andheri station")["type"] == "action_request"


def test_busy_while_checking(phone):
    phone.say("Powai to Andheri station")
    assert phone.say("hello")["code"] == "busy"


def test_app_not_installed_is_skipped(phone):
    resp = phone.say("Powai to Andheri station, cheapest")
    resp = phone.post(
        {"type": "tool_result", "action_id": resp["action_id"], "status": "app_not_installed"}
    )
    rec, apps = phone.answer_fare_checks(resp)
    assert rec["type"] == "recommendation" and len(apps) == 2


def test_ios_device_cannot_request_fare_checks(client, clock):
    from tests.conftest import Phone

    ios = Phone(client, clock, platform="ios")
    resp = ios.say("Powai to Andheri station")
    assert resp["type"] == "assistant_message"
    assert "can't check live fares" in resp["text"]
