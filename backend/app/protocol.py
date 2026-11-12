"""Wire protocol between a client (phone) and the backend.

Every POST to /v1/sessions/{id}/messages carries exactly one Inbound message and gets
exactly one Outbound response. See docs/protocol.md for examples.

Anything the phone reads from another app's screen arrives only as the strictly typed
FareObservation below. Free text from screens is never accepted (extra="forbid"), so it
cannot reach the LLM prompt.
"""

from datetime import datetime
from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class App(StrEnum):
    UBER = "uber"
    OLA = "ola"
    RAPIDO = "rapido"


class RideType(StrEnum):
    CAB = "cab"
    AUTO = "auto"
    BIKE = "bike"


class RideCategory(StrEnum):
    """What the user picks: Auto (rickshaw), Bike, Cab, or Cab XL (SUV / 6-seater)."""

    AUTO = "auto"
    BIKE = "bike"
    CAB = "cab"
    PREMIUM = "premium"  # sedans and luxury: Premier AC, Prime Sedan, Cab Premium, Black
    CAB_XL = "cab_xl"


class Platform(StrEnum):
    ANDROID = "android"
    IOS = "ios"


Label = Annotated[str, Field(min_length=1, max_length=80)]
Id = Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")]


class Place(Strict):
    # Coordinates are optional: a place Hail can't geocode is passed by name, and the phone
    # finds it with the ride app's own search (deep links need coordinates; navigation doesn't).
    lat: float | None = Field(default=None, ge=-90, le=90)
    lng: float | None = Field(default=None, ge=-180, le=180)
    label: Label

    @model_validator(mode="after")
    def _both_or_neither(self) -> "Place":
        if (self.lat is None) != (self.lng is None):
            raise ValueError("lat and lng must be given together")
        return self


class FareObservation(Strict):
    app: App
    ride_type: RideType
    fare_min: float = Field(ge=0, le=20000)
    fare_max: float = Field(ge=0, le=20000)
    currency: Literal["INR"] = "INR"
    eta_minutes: int | None = Field(default=None, ge=0, le=180)
    surge_flag: bool = False
    captured_at: datetime
    confidence: float = Field(ge=0, le=1)
    # Identifier of the reader's screen-matching rule, e.g. "rapido.fare_list.v1".
    # Restricted charset so it can never carry prose.
    screen_signature: str = Field(pattern=r"^[A-Za-z0-9_.:-]{1,64}$")
    # Catalog key of the ride product (e.g. "uber_go_ac"), from GET /v1/config/readers.
    # A key, not the text read from the screen; unknown keys are discarded by the backend.
    product: str | None = Field(default=None, pattern=r"^[a-z0-9_]{1,40}$")

    @model_validator(mode="after")
    def _range(self) -> "FareObservation":
        if self.fare_max < self.fare_min:
            raise ValueError("fare_max must be >= fare_min")
        return self

    @property
    def fare_mid(self) -> float:
        return (self.fare_min + self.fare_max) / 2


class Capabilities(Strict):
    """What a client can do. iOS clients cannot read other apps, so they declare
    can_check_fares=false and the agent never asks them to."""

    can_check_fares: bool = False
    can_book: bool = False


# ---------- Inbound (client -> backend) ----------


class UserMessage(Strict):
    type: Literal["user_message"] = "user_message"
    text: str = Field(min_length=1, max_length=500)
    # Device location, only sent with a live user request. Used for "from here".
    location: Place | None = None
    # Ride apps the user has connected (installed, logged in, switched on) on this phone.
    # None means "not stated", so all apps are allowed.
    available_apps: list[App] | None = Field(default=None, max_length=len(App))
    # "voice": the user spoke (the phone turned speech into this text). Replies are read aloud.
    input_mode: Literal["text", "voice"] = "text"
    # The ride-type dropdown or a tapped choice. None: from the text, or Hail asks.
    ride_category: RideCategory | None = None
    # A tapped favourite place: used as the destination as-is (no lookup).
    destination: Place | None = None


class HandoffOutcome(Strict):
    opened: bool


class ToolResult(Strict):
    type: Literal["tool_result"] = "tool_result"
    action_id: Id
    status: Literal["ok", "failed", "app_not_installed", "not_logged_in", "stopped_by_user"]
    observations: list[FareObservation] = Field(default_factory=list, max_length=30)
    handoff: HandoffOutcome | None = None


class Confirmation(Strict):
    """The user's tap on one option of a recommendation card (or Cancel: accept=false)."""

    type: Literal["confirmation"] = "confirmation"
    option_id: Id
    token: str = Field(min_length=16, max_length=128)
    accept: bool = True
    # How the user picked: a tap, or a spoken pick matched ON THE PHONE by fixed rules
    # (VoicePick.kt), never by the model. Same gate either way.
    via: Literal["tap", "voice"] = "tap"


Inbound = Annotated[UserMessage | ToolResult | Confirmation, Field(discriminator="type")]


# ---------- Outbound (backend -> client) ----------


class OptionView(Strict):
    option_id: str
    app: App
    ride_type: RideType
    product_name: str | None = None  # trusted display name from the catalog, e.g. "Uber Go AC"
    fare_min: float
    fare_max: float
    eta_minutes: int | None
    surge_flag: bool


class Savings(Strict):
    amount: int
    compared_against: OptionView


class ChoiceOption(OptionView):
    # What picking this option saves vs the priciest comparable option (None if nothing).
    saves: int | None = None


class CheckFaresAction(Strict):
    kind: Literal["CHECK_FARES"] = "CHECK_FARES"
    app: App
    pickup: Place
    drop: Place
    ride_types: list[RideType]
    ride_category: RideCategory | None = None
    # True when pickup is the phone's own location: the ride app's default pickup is used as-is.
    pickup_is_current_location: bool = False


class OpenAppAction(Strict):
    """Open the chosen app with the trip filled in. The user taps Book there; Hail never books."""

    kind: Literal["OPEN_APP"] = "OPEN_APP"
    app: App
    option_id: str
    ride_type: RideType
    pickup: Place
    drop: Place
    product: str | None = None  # catalog key of the chosen ride, so the phone can select it
    product_name: str | None = None
    pickup_is_current_location: bool = False


class ConfirmationPrompt(Strict):
    token: str
    expires_at: datetime


class _Out(Strict):
    session_id: str


class Ask(Strict):
    """A question with fixed answers; the phone shows them as buttons (and accepts speech)."""

    field: Literal["ride_category"] = "ride_category"
    choices: list[RideCategory]


class AssistantMessage(_Out):
    type: Literal["assistant_message"] = "assistant_message"
    text: str
    savings: Savings | None = None
    # On a finished trip with no saving: why, so the app can say so instead of showing nothing.
    # "no_comparable": no like-for-like ride from another app was checked.
    # "not_cheapest": like-for-like rides were checked, and the pick wasn't the cheapest.
    no_saving_reason: Literal["no_comparable", "not_cheapest"] | None = None
    done: bool = False
    ask: Ask | None = None


class ActionRequest(_Out):
    type: Literal["action_request"] = "action_request"
    action_id: str
    action: CheckFaresAction


class Recommendation(_Out):
    """All options, best-first for the user's preference, with one highlighted. The user picks."""

    type: Literal["recommendation"] = "recommendation"
    text: str
    recommended_option_id: str
    ride_category: RideCategory | None = None
    drop: Place | None = None  # so the phone can offer "♡ Save to favourites"
    options: list[ChoiceOption]
    confirmation: ConfirmationPrompt
    # When the prices were read from the apps (the oldest one), and whether they are live
    # readings rather than test/recorded data.
    prices_checked_at: datetime | None = None
    live: bool = True


class HandoffRequest(_Out):
    """The ONLY way OPEN_APP leaves the backend. Built solely by gate.authorize_handoff."""

    type: Literal["handoff_request"] = "handoff_request"
    action_id: str
    action: OpenAppAction


class ErrorResponse(_Out):
    type: Literal["error"] = "error"
    code: str
    message: str
    retryable: bool = False


Outbound = AssistantMessage | ActionRequest | Recommendation | HandoffRequest | ErrorResponse


# ---------- Device registration ----------


InviteCode = Annotated[str, Field(pattern=r"^[A-Za-z0-9-]{4,32}$")]


class DeviceRegistration(Strict):
    platform: Platform
    capabilities: Capabilities
    invite_code: InviteCode | None = None


class InviteRequestIn(Strict):
    """Someone asks to join the beta. `contact` is a phone number or email they chose to give."""

    contact: Annotated[str, Field(min_length=3, max_length=120)]
    city: Annotated[str, Field(max_length=60)] | None = None


class FavoriteIn(Strict):
    label: Label
    lat: float | None = Field(default=None, ge=-90, le=90)
    lng: float | None = Field(default=None, ge=-180, le=180)


class Favorite(FavoriteIn):
    id: int


class Favorites(Strict):
    favorites: list[Favorite]


class DeviceCredentials(Strict):
    device_id: str
    token: str
