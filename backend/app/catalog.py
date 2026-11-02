"""Ride-product catalog, loaded from readers_config.json (the same file the phone reads).

The phone sends a product KEY (e.g. "uber_go_ac") with each observation, never the text it
read. The backend accepts only keys in this catalog for that app, and maps them to trusted
display names. Screen text therefore never reaches the model or the database.
"""

import json
from functools import lru_cache
from pathlib import Path

from pydantic import BaseModel

from app.protocol import App, FareObservation, RideCategory, RideType

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


# Which catalog classes belong to each category the user chooses from.
CATEGORY_CLASSES: dict[RideCategory, set[str]] = {
    RideCategory.AUTO: {"auto", "auto_priority"},
    RideCategory.BIKE: {"bike"},
    RideCategory.CAB: {"cab_non_ac", "cab_ac", "cab_priority", "cab_luggage"},
    RideCategory.PREMIUM: {"cab_premium", "cab_luxury"},
    RideCategory.CAB_XL: {"cab_xl"},
}
RIDE_TYPES_FOR: dict[RideCategory, list[RideType]] = {
    RideCategory.AUTO: [RideType.AUTO],
    RideCategory.BIKE: [RideType.BIKE],
    RideCategory.CAB: [RideType.CAB],
    RideCategory.PREMIUM: [RideType.CAB],
    RideCategory.CAB_XL: [RideType.CAB],
}


def category_of(ob: FareObservation) -> RideCategory:
    p = lookup(ob.app, ob.product)
    if p and p.comparable_class:
        for cat, classes in CATEGORY_CLASSES.items():
            if p.comparable_class in classes:
                return cat
    return RideCategory(ob.ride_type.value)  # no catalog product: cab/auto/bike as is


def lookup(app: App, key: str | None) -> Product | None:
    if key is None:
        return None
    return products().get((app, key))
