"""Server-side session state. Persisted as JSON by the store."""

from datetime import datetime
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, Field

from app.protocol import App, FareObservation, Place, RideType


class Status(StrEnum):
    IDLE = "idle"  # waiting for the user to say something
    AWAITING_TOOL_RESULT = "awaiting_tool_result"  # phone is checking fares
    AWAITING_CONFIRMATION = "awaiting_confirmation"  # options card shown, user to pick
    HANDING_OFF = "handing_off"  # OPEN_APP sent to phone
    DONE = "done"  # chosen app opened; the user books there


class ResolvedPlace(BaseModel):
    place_id: str
    city: str
    place: Place


class Option(BaseModel):
    option_id: str
    round: int
    observation: FareObservation


class PendingAction(BaseModel):
    action_id: str
    kind: Literal["CHECK_FARES", "OPEN_APP"]
    app: App
    option_id: str | None = None
    ride_types: list[RideType] = Field(default_factory=list)


class FareCheckBatch(BaseModel):
    """One request_fare_check tool call, expanded into one phone action per app."""

    tool_call_id: str
    pickup_id: str
    drop_id: str
    ride_types: list[RideType]
    queue: list[App]  # apps not yet sent to the phone
    results: dict[str, str] = Field(default_factory=dict)  # app -> status


class PendingConfirmation(BaseModel):
    option_ids: list[str]  # every option shown on the card
    token_hash: str
    shown_at: datetime
    expires_at: datetime
    used: bool = False


class SessionState(BaseModel):
    status: Status = Status.IDLE
    messages: list[dict[str, Any]] = Field(default_factory=list)  # LLM history, OpenAI format
    places: dict[str, ResolvedPlace] = Field(default_factory=dict)
    device_location: Place | None = None
    options: dict[str, Option] = Field(default_factory=dict)
    round: int = 0
    batch: FareCheckBatch | None = None
    pending_action: PendingAction | None = None
    trip: tuple[str, str] | None = None  # (pickup_id, drop_id) of current round
    recommended_option_id: str | None = None
    recommendation_reason: str | None = None
    confirmation: PendingConfirmation | None = None
    chosen_option_id: str | None = None
    available_apps: list[App] | None = None  # apps the user connected on this phone
    ranked_option_ids: list[str] = Field(default_factory=list)  # last rank_options order
    action_seq: int = 0

    def next_action_id(self) -> str:
        self.action_seq += 1
        return f"act_{self.action_seq}"

    def current_options(self) -> list[Option]:
        return [o for o in self.options.values() if o.round == self.round]
