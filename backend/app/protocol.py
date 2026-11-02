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


class BookingOutcome(Strict):
    booked: bool


class ToolResult(Strict):
    type: Literal["tool_result"] = "tool_result"
    action_id: Id
    status: Literal["ok", "failed", "app_not_installed", "not_logged_in", "stopped_by_user"]
    observations: list[FareObservation] = Field(default_factory=list, max_length=12)
    booking: BookingOutcome | None = None


class Confirmation(Strict):
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
    fare_min: float
    fare_max: float
    eta_minutes: int | None
    surge_flag: bool


class Savings(Strict):
    amount: int
    compared_against: OptionView


class CheckFaresAction(Strict):
    kind: Literal["CHECK_FARES"] = "CHECK_FARES"
    app: App
    pickup: Place
    drop: Place
    ride_types: list[RideType]


class BookAction(Strict):
    kind: Literal["BOOK"] = "BOOK"
    app: App
    option_id: str
    ride_type: RideType
    pickup: Place
    drop: Place


class ConfirmationPrompt(Strict):
    option_id: str
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
    type: Literal["recommendation"] = "recommendation"
    text: str
    recommended: OptionView
    options: list[OptionView]
    savings_if_booked: Savings | None
    confirmation: ConfirmationPrompt


class BookingRequest(_Out):
    """The ONLY way a BOOK action leaves the backend. Built solely by gate.authorize_booking."""

    type: Literal["booking_request"] = "booking_request"
    action_id: str
    action: BookAction


class ErrorResponse(_Out):
    type: Literal["error"] = "error"
    code: str
    message: str
    retryable: bool = False


Outbound = AssistantMessage | ActionRequest | Recommendation | BookingRequest | ErrorResponse


# ---------- Device registration ----------


class DeviceRegistration(Strict):
    platform: Platform
    capabilities: Capabilities


class DeviceCredentials(Strict):
    device_id: str
    token: str
