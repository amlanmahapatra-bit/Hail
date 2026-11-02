"""The reader config is served to phones and edited by hand; keep it consistent."""

import json
import re

from app.catalog import load_config, products
from app.protocol import App
from tests.conftest import RECORDED


def test_product_keys_unique_prefixed_and_valid():
    cfg = load_config()
    keys = [p.key for p in products().values()]
    assert len(keys) == len(set(keys))
    for (app, key), p in products().items():
        assert key.startswith(app.value + "_")
        assert re.fullmatch(r"[a-z0-9_]{1,40}", key)
        if p.exclude is None:
            assert p.ride_type is not None and p.comparable_class in cfg["classes"]


def test_every_app_has_a_reader_and_patterns_compile():
    cfg = load_config()
    assert set(cfg["apps"]) == {a.value for a in App}
    re.compile(cfg["price_pattern"])
    for app in cfg["apps"].values():
        for p in app["eta_patterns"]:
            re.compile(p)
        assert app["screens"]["fare_list"]["any_text"] or app["screens"]["fare_list"]["all_text"]


def test_recorded_fares_use_known_rideable_products():
    fares = json.loads(RECORDED.read_text(encoding="utf-8"))["fares"]
    for app, rows in fares.items():
        for row in rows:
            p = products()[(App(app), row["product"])]
            assert p.exclude is None and p.ride_type.value == row["ride_type"]
