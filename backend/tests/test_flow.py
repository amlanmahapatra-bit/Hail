"""End to end with the fake LLM and the founder's real recorded fares (Bangalore, 2026-11-02)."""

from datetime import timedelta

from tests.conftest import FARES, Phone, observations


def _by_id(rec):
    return {o["option_id"]: o for o in rec["options"]}


def _best(rec):
    return _by_id(rec)[rec["recommended_option_id"]]


# ---------- ride category: asked once, in code ----------


def test_no_ride_type_means_hail_asks_first(phone):
    resp = phone.say("Powai to Andheri station, cheapest")
    assert resp["type"] == "assistant_message"
    assert resp["ask"] == {
        "field": "ride_category",
        "choices": ["auto", "bike", "cab", "premium", "cab_xl"],
    }
    # the answer (a tapped button) continues the same trip
    resp = phone.say("Auto", category="auto")
    assert resp["type"] == "action_request"
    assert resp["action"]["ride_category"] == "auto" and resp["action"]["ride_types"] == ["auto"]
    rec, apps = phone.answer_fare_checks(resp)
    assert sorted(apps) == ["ola", "rapido", "uber"]
    assert {o["ride_type"] for o in rec["options"]} == {"auto"}


def test_typed_or_spoken_answer_works_too(phone):
    phone.say("Powai to Andheri station")
    resp = phone.say("rickshaw please")
    assert resp["type"] == "action_request" and resp["action"]["ride_category"] == "auto"


def test_ride_type_in_the_request_skips_the_question(phone):
    for words, cat in [
        ("by rickshaw", "auto"),
        ("big cab", "cab_xl"),
        ("XL", "cab_xl"),
        ("bike", "bike"),
        ("car", "cab"),
    ]:
        p = Phone(phone.c, phone.clock)
        resp = p.say(f"Powai to Andheri station, {words}")
        assert resp["type"] == "action_request", words
        assert resp["action"]["ride_category"] == cat, words


def test_dropdown_category_skips_the_question(phone):
    resp = phone.say("Powai to Andheri station", category="cab_xl")
    assert resp["action"]["ride_category"] == "cab_xl"


def test_new_destination_asks_again(phone):
    rec, _ = phone.answer_fare_checks(phone.say("Powai to Andheri station", category="auto"))
    assert rec["type"] == "recommendation"
    resp = phone.say("Powai to BKC")
    assert resp["type"] == "assistant_message" and resp["ask"] is not None


# ---------- each category shows only its rides, best first ----------


def test_auto_cheapest(phone):
    resp = phone.say("Powai to Andheri station, cheapest", category="auto")
    a = resp["action"]
    rec, _ = phone.answer_fare_checks(resp)
    assert rec["ride_category"] == "auto"
    names = [(o["app"], o["product_name"]) for o in rec["options"]]
    assert names[0] == ("ola", "Auto") and len(names) == 6  # incl. two priority autos
    assert _best(rec)["saves"] == 3  # Ola Rs46 vs Uber Auto Rs49.40

    handoff = phone.confirm(rec)
    assert handoff["action"] == {
        "kind": "OPEN_APP",
        "app": "ola",
        "option_id": rec["recommended_option_id"],
        "ride_type": "auto",
        "pickup": a["pickup"],
        "drop": a["drop"],
        "pickup_is_current_location": False,
        "product": "ola_auto",
        "product_name": "Auto",
    }
    done = phone.opened(handoff)
    assert (
        done["done"] is True
        and "Pick Auto and tap Book" in done["text"]
        and "You save ₹3" in done["text"]
    )


def test_cab_is_economy_only(phone):
    rec, _ = phone.answer_fare_checks(
        phone.say("Powai to Andheri station, cheapest", category="cab")
    )
    names = {o["product_name"] for o in rec["options"]}
    assert len(rec["options"]) == 10
    assert not names & {"Premier AC", "Black", "Prime Sedan", "Prime Plus", "Cab Premium"}
    best = _best(rec)
    # Rapido Cab Non AC Rs101 vs Ola Mini Non AC Rs112 (same class)
    assert (best["app"], best["product_name"], best["saves"]) == ("rapido", "Cab Non AC", 11)


def test_premium_finds_the_promo_sedan(phone):
    rec, _ = phone.answer_fare_checks(
        phone.say("Powai to Andheri station, cheapest", category="premium")
    )
    assert sorted(o["product_name"] for o in rec["options"]) == [
        "Black",
        "Cab Premium",
        "Premier AC",
        "Prime Plus",
        "Prime Sedan",
    ]
    best = _best(rec)
    assert (best["app"], best["product_name"], best["saves"]) == ("uber", "Premier AC", 67)


def test_premium_words(phone):
    for words in ["sedan", "premium cab", "uber black"]:
        p = Phone(phone.c, phone.clock)
        assert p.say(f"Powai to Andheri station, {words}")["action"]["ride_category"] == "premium"


def test_cab_xl(phone):
    rec, _ = phone.answer_fare_checks(
        phone.say("Powai to Andheri station, cheapest", category="cab_xl")
    )
    assert sorted(o["product_name"] for o in rec["options"]) == [
        "Cab XL",
        "Premier XL",
        "Prime SUV",
        "UberXL",
    ]
    assert (_best(rec)["product_name"], _best(rec)["saves"]) == ("Prime SUV", 100)


def test_bike(phone):
    rec, _ = phone.answer_fare_checks(phone.say("Powai to Andheri station", category="bike"))
    assert {o["ride_type"] for o in rec["options"]} == {"bike"} and len(rec["options"]) == 5


def test_user_can_pick_a_non_recommended_option(phone):
    rec, _ = phone.answer_fare_checks(phone.say("Powai to Andheri station", category="auto"))
    uber = next(o for o in rec["options"] if o["app"] == "uber" and o["product_name"] == "Auto")
    handoff = phone.confirm(rec, option_id=uber["option_id"])
    assert handoff["action"]["app"] == "uber"
    assert "Opened Uber" in phone.opened(handoff)["text"]


# ---------- live prices ----------


def test_card_says_when_prices_were_checked_and_if_live(phone):
    rec, _ = phone.answer_fare_checks(phone.say("Powai to Andheri station", category="auto"))
    assert rec["prices_checked_at"] is not None
    assert rec["live"] is False  # recorded test fares are labelled, never passed off as live


def test_live_readings_are_marked_live(phone):
    resp = phone.say("Powai to Andheri station", category="auto")
    while resp["type"] == "action_request":
        obs = observations(resp["action"]["app"], ["auto"], phone.clock())
        for o in obs:
            o["screen_signature"] = f"{o['app']}.fare_list.draft1"
        resp = phone.post(
            {
                "type": "tool_result",
                "action_id": resp["action_id"],
                "status": "ok",
                "observations": obs,
            }
        )
    assert resp["live"] is True


def test_prices_must_arrive_within_a_minute(phone):
    resp = phone.say("Powai to Andheri station", category="auto")
    app = resp["action"]["app"]
    for age, kept in [(61, False), (30, True)]:
        p = Phone(phone.c, phone.clock)
        resp = p.say("Powai to Andheri station", category="auto")
        obs = observations(resp["action"]["app"], ["auto"], p.clock() - timedelta(seconds=age))
        resp = p.post(
            {
                "type": "tool_result",
                "action_id": resp["action_id"],
                "status": "ok",
                "observations": obs,
            }
        )
        rec, _ = p.answer_fare_checks(resp)
        assert any(o["app"] == app for o in rec["options"]) is kept, age


def test_stale_prices_are_rejected(phone):
    resp = phone.say("Powai to Andheri station", category="auto")
    while resp["type"] == "action_request":
        old = phone.clock() - timedelta(minutes=10)
        resp = phone.post(
            {
                "type": "tool_result",
                "action_id": resp["action_id"],
                "status": "ok",
                "observations": observations(resp["action"]["app"], ["auto"], old),
            }
        )
    assert resp["type"] == "assistant_message"  # nothing fresh to show


def test_model_sees_only_the_chosen_category(phone):
    rec, _ = phone.answer_fare_checks(phone.say("Powai to Andheri station", category="cab_xl"))
    assert len(rec["options"]) == 4  # not 30


# ---------- other behaviour ----------


def test_luggage_excludes_bike(phone):
    rec, _ = phone.answer_fare_checks(
        phone.say("Powai to Andheri station, cheapest, with luggage", category="auto")
    )
    assert all(o["ride_type"] == "auto" for o in rec["options"])


def test_only_connected_apps_are_checked(phone):
    rec, apps = phone.answer_fare_checks(
        phone.say("Powai to Andheri station", apps=["ola", "rapido"], category="auto")
    )
    assert sorted(apps) == ["ola", "rapido"]
    assert {o["app"] for o in rec["options"]} == {"ola", "rapido"}


def test_no_connected_apps(phone):
    out = phone.say("Powai to Andheri station", apps=[], category="auto")
    assert out["type"] == "assistant_message"


def test_unknown_place_is_passed_by_name(phone):
    resp = phone.say("Mesa School of Business to FF21 Maya Student Hostel", category="auto")
    assert resp["type"] == "action_request"
    assert resp["action"]["drop"] == {"lat": None, "lng": None, "label": "ff21 maya student hostel"}


def test_stop_mid_check(phone):
    resp = phone.say("Powai to Andheri station", category="auto")
    out = phone.post(
        {"type": "tool_result", "action_id": resp["action_id"], "status": "stopped_by_user"}
    )
    assert out["type"] == "assistant_message" and "Stopped" in out["text"]
    assert phone.say("Powai to Andheri station", category="auto")["type"] == "action_request"


def test_busy_while_checking(phone):
    phone.say("Powai to Andheri station", category="auto")
    assert phone.say("hello")["code"] == "busy"


def test_app_not_installed_is_skipped(phone):
    resp = phone.say("Powai to Andheri station", category="auto")
    resp = phone.post(
        {"type": "tool_result", "action_id": resp["action_id"], "status": "app_not_installed"}
    )
    rec, apps = phone.answer_fare_checks(resp)
    assert rec["type"] == "recommendation" and len(apps) == 2


def test_handoff_failure_is_reported(phone):
    rec, _ = phone.answer_fare_checks(phone.say("Powai to Andheri station", category="auto"))
    out = phone.opened(phone.confirm(rec), ok=False)
    assert "couldn't open" in out["text"] and out["done"] is False


def test_unknown_or_excluded_products_are_dropped(phone):
    resp = phone.say("Powai to Andheri station", category="auto")
    app = resp["action"]["app"]
    good = observations(app, ["auto"], phone.clock())
    bad = [good[0] | {"product": "made_up_product"}, good[0] | {"product": f"{app}_go_rentals"}]
    resp = phone.post(
        {
            "type": "tool_result",
            "action_id": resp["action_id"],
            "status": "ok",
            "observations": good + bad,
        }
    )
    rec, _ = phone.answer_fare_checks(resp)
    assert sum(o["app"] == app for o in rec["options"]) == len(
        [f for f in FARES[app] if f["ride_type"] == "auto"]
    )


def test_ios_device_cannot_request_fare_checks(client, clock):
    ios = Phone(client, clock, platform="ios")
    resp = ios.say("Powai to Andheri station", category="auto")
    assert resp["type"] == "assistant_message"
    assert "can't check live fares" in resp["text"]


def test_no_pickup_means_current_location(phone):
    here = {"lat": 12.8875, "lng": 77.5970, "label": "Current location"}
    resp = phone.post(
        {
            "type": "user_message",
            "text": "to Koramangala, cheapest",
            "location": here,
            "ride_category": "auto",
        }
    )
    assert resp["action"]["pickup"] == here and resp["action"]["pickup_is_current_location"]
    rec, _ = phone.answer_fare_checks(resp)
    assert phone.confirm(rec)["action"]["pickup_is_current_location"] is True


def test_named_pickup_is_not_current_location(phone):
    resp = phone.say("Powai to Andheri station", category="auto")
    assert resp["action"]["pickup_is_current_location"] is False


def test_no_pickup_without_location_asks(phone):
    assert phone.say("to Koramangala", category="auto")["type"] == "assistant_message"


def test_ambiguous_place_uses_the_city_the_phone_is_in(phone):
    blr = {"lat": 12.8875, "lng": 77.5970, "label": "Current location"}
    resp = phone.post(
        {"type": "user_message", "text": "to airport", "location": blr, "ride_category": "cab"}
    )
    assert resp["action"]["drop"]["label"] == "Kempegowda Airport"
    mum = {"lat": 19.1197, "lng": 72.8464, "label": "Current location"}
    resp = phone.post(
        {"type": "user_message", "text": "to airport", "location": mum, "ride_category": "cab"},
        session="mumbai-session-1",
    )
    assert resp["action"]["drop"]["label"] == "Mumbai Airport T2"
