"""Ride-product catalog, loaded from readers_config.json (the same file the phone reads).

The phone sends a product KEY (e.g. "uber_go_ac") with each observation, never the text it
read. The backend accepts only keys in this catalog for that app, and maps them to trusted
display names. Screen text therefore never reaches the model or the database.
"""

import json
from functools import lru_cache
from pathlib import Path

from pydantic import BaseModel

from app.protocol import App, RideType

CONFIG_PATH = Path(__file__).parent / "readers_config.json"


class Product(BaseModel):
    key: str
    app: App
    name: str  # display name, as the app shows it
    ride_type: RideType | None = None
    comparable_class: str | None = None  # savings are only computed within a class
    exclude: str | None = None  # rental / parcel / pet / aggregate / specialty


@lru_cache
def load_config() -> dict:
    return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))


@lru_cache
def products() -> dict[tuple[App, str], Product]:
    out: dict[tuple[App, str], Product] = {}
    for app_name, app_cfg in load_config()["apps"].items():
        app = App(app_name)
        for name, p in app_cfg.get("products", {}).items():
            prod = Product(
                key=p["key"],
                app=app,
                name=name,
                ride_type=RideType(p["ride_type"]) if p.get("ride_type") else None,
                comparable_class=p.get("class"),
                exclude=p.get("exclude"),
            )
            out[(app, prod.key)] = prod
    return out


def lookup(app: App, key: str | None) -> Product | None:
    if key is None:
        return None
    return products().get((app, key))
