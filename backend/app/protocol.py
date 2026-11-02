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


class Platform(StrEnum):
    ANDROID = "android"
    IOS = "ios"


Label = Annotated[str, Field(min_length=1, max_length=80)]
Id = Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")]


class Place(Strict):
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    label: Label


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


class OpenAppAction(Strict):
    """Open the chosen app with the trip filled in. The user taps Book there; Hail never books."""

    kind: Literal["OPEN_APP"] = "OPEN_APP"
    app: App
    option_id: str
    ride_type: RideType
    pickup: Place
    drop: Place


class ConfirmationPrompt(Strict):
    token: str
    expires_at: datetime


class _Out(Strict):
    session_id: str


class AssistantMessage(_Out):
    type: Literal["assistant_message"] = "assistant_message"
    text: str
    savings: Savings | None = None
    done: bool = False


class ActionRequest(_Out):
    type: Literal["action_request"] = "action_request"
    action_id: str
    action: CheckFaresAction


class Recommendation(_Out):
    """All options, best-first for the user's preference, with one highlighted. The user picks."""

    type: Literal["recommendation"] = "recommendation"
    text: str
    recommended_option_id: str
    options: list[ChoiceOption]
    confirmation: ConfirmationPrompt


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


class DeviceRegistration(Strict):
    platform: Platform
    capabilities: Capabilities


class DeviceCredentials(Strict):
    device_id: str
    token: str
