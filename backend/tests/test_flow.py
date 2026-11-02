"""Acceptance: 'Powai to Andheri station, cheapest' end to end with the fake LLM."""

from tests.conftest import Phone


def _by_id(rec):
    return {o["option_id"]: o for o in rec["options"]}


def test_full_loop_cheapest(phone):
    """Real fares from the founder's recordings (Bangalore, 2026-11-02)."""
    resp = phone.say("Powai to Andheri station, cheapest")
    assert resp["type"] == "action_request"
    a = resp["action"]
    assert a["kind"] == "CHECK_FARES"
    assert a["pickup"]["label"] == "Powai" and a["drop"]["label"] == "Andheri Station"

    rec, apps = phone.answer_fare_checks(resp)
    assert sorted(apps) == ["ola", "rapido", "uber"]
    assert rec["type"] == "recommendation"
    assert len(rec["options"]) == 30  # 11 Uber + 9 Ola + 10 Rapido rideable products
    fares = [o["fare_min"] for o in rec["options"]]
    assert fares == sorted(fares)  # cheapest first
    best = _by_id(rec)[rec["recommended_option_id"]]
    # Ola Bike and Rapido Bike Direct are both Rs40; Ola wins on ETA (1 vs 3 min)
    assert (best["app"], best["product_name"]) == ("ola", "Bike")
    assert best["saves"] == 4  # vs Uber Scooty Rs44.18, same "bike" class

    handoff = phone.confirm(rec)
    assert handoff["type"] == "handoff_request"
    assert handoff["action"] == {
        "kind": "OPEN_APP",
        "app": "ola",
        "option_id": rec["recommended_option_id"],
        "ride_type": "bike",
        "pickup": a["pickup"],
        "drop": a["drop"],
    }

    done = phone.opened(handoff)
    assert done["type"] == "assistant_message" and done["done"] is True
    assert "Opened Ola" in done["text"] and "You save ₹4" in done["text"]


def test_savings_compare_like_for_like(phone):
    rec, _ = phone.answer_fare_checks(phone.say("Powai to Andheri station, cheapest"))
    by_name = {(o["app"], o["product_name"]): o for o in rec["options"]}
    # Uber Premier AC (Rs64.98 promo) vs the priciest sedan elsewhere: Rapido Cab Premium Rs132
    assert by_name[("uber", "Premier AC")]["saves"] == 67
    # Ola Prime SUV Rs139 vs Uber Premier XL Rs238.60 (both 6-seaters)
    assert by_name[("ola", "Prime SUV")]["saves"] == 100
    # the priciest in its class saves nothing
    assert by_name[("uber", "Premier XL")]["saves"] is None


def test_user_can_pick_a_non_recommended_option(phone):
    rec, _ = phone.answer_fare_checks(phone.say("Powai to Andheri station, cheapest"))
    ola_auto = next(o for o in rec["options"] if (o["app"], o["ride_type"]) == ("ola", "auto"))
    handoff = phone.confirm(rec, option_id=ola_auto["option_id"])
    assert handoff["type"] == "handoff_request"
    assert handoff["action"]["app"] == "ola" and handoff["action"]["ride_type"] == "auto"
    done = phone.opened(handoff)
    assert "Opened Ola" in done["text"]
    assert done["savings"]["amount"] == 3  # Ola Auto Rs46 vs Uber Auto Rs49.40


def test_handoff_failure_is_reported(phone):
    rec, _ = phone.answer_fare_checks(phone.say("Powai to Andheri station"))
    out = phone.opened(phone.confirm(rec), ok=False)
    assert "couldn't open" in out["text"] and out["done"] is False


def test_luggage_excludes_bike_from_the_list(phone):
    rec, _ = phone.answer_fare_checks(phone.say("Powai to Andheri station, cheapest, with luggage"))
    assert all(o["ride_type"] != "bike" for o in rec["options"])
    best = _by_id(rec)[rec["recommended_option_id"]]
    assert (best["app"], best["ride_type"]) == ("ola", "auto")  # Rs46
    assert best["saves"] == 3


def test_unknown_or_excluded_products_are_dropped(phone):
    resp = phone.say("Powai to Andheri station")
    app = resp["action"]["app"]
    from tests.conftest import observations

    good = observations(app, ["cab", "auto", "bike"], phone.clock())
    bad = [
        good[0] | {"product": "made_up_product"},
        good[0] | {"product": "uber_go_rentals"},
        good[0] | {"product": "ola_bike", "app": app},
    ]
    resp = phone.post(
        {
            "type": "tool_result",
            "action_id": resp["action_id"],
            "status": "ok",
            "observations": good + bad,
        }
    )
    rec, _ = phone.answer_fare_checks(resp)
    from tests.conftest import FARES

    assert sum(o["app"] == app for o in rec["options"]) == len(FARES[app])


def test_ride_type_request_only_checks_that_type(phone):
    resp = phone.say("Powai to BKC by cab, cheapest")
    assert resp["action"]["ride_types"] == ["cab"]
    rec, _ = phone.answer_fare_checks(resp)
    assert {o["ride_type"] for o in rec["options"]} == {"cab"}


def test_only_connected_apps_are_checked(phone):
    rec, apps = phone.answer_fare_checks(
        phone.say("Powai to Andheri station", apps=["ola", "rapido"])
    )
    assert sorted(apps) == ["ola", "rapido"]
    assert {o["app"] for o in rec["options"]} == {"ola", "rapido"}


def test_no_connected_apps(phone):
    out = phone.say("Powai to Andheri station", apps=[])
    assert out["type"] == "assistant_message"


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
    ios = Phone(client, clock, platform="ios")
    resp = ios.say("Powai to Andheri station")
    assert resp["type"] == "assistant_message"
    assert "can't check live fares" in resp["text"]
