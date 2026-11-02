"""The agent loop: one inbound message in, exactly one outbound response out.

The LLM chooses which tools to call, but code owns the state machine, the data and the
confirmation gate. The model never sees confirmation tokens, raw screen text, or
coordinates (place_ids only).
"""

import json
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from pydantic import ValidationError

from app import gate
from app.config import Settings
from app.geocode import geocode
from app.llm import LLMClient, LLMError, ToolCall
from app.protocol import (
    ActionRequest,
    App,
    AssistantMessage,
    Capabilities,
    CheckFaresAction,
    Confirmation,
    ErrorResponse,
    FareObservation,
    Outbound,
    Recommendation,
    RideType,
    ToolResult,
    UserMessage,
)
from app.ranking import RankRequest, rank, savings, view
from app.state import FareCheckBatch, Option, PendingAction, ResolvedPlace, SessionState, Status
from app.tools import system_prompt, tools_for

IST = ZoneInfo("Asia/Kolkata")
CURRENT_LOCATION = {"current location", "here", "my location", "current"}


def is_late_night(now: datetime) -> bool:
    h = now.astimezone(IST).hour
    return h >= 22 or h < 5


class ToolError(Exception):
    pass


@dataclass
class Hooks:
    """Side effects the agent needs from the outside world (rate limiting, fare logging)."""

    allow_fare_check: Callable[[], bool]
    record_observations: Callable[[list[FareObservation]], None]


class Agent:
    def __init__(self, llm: LLMClient, settings: Settings):
        self.llm = llm
        self.settings = settings

    # ------------------------------------------------------------------ entry
    def handle(
        self,
        session_id: str,
        state: SessionState,
        caps: Capabilities,
        msg: UserMessage | ToolResult | Confirmation,
        now: datetime,
        hooks: Hooks,
    ) -> Outbound:
        ctx = _Ctx(session_id, state, caps, now, hooks)
        try:
            if isinstance(msg, UserMessage):
                return self._on_user_message(ctx, msg)
            if isinstance(msg, ToolResult):
                return self._on_tool_result(ctx, msg)
            return self._on_confirmation(ctx, msg)
        except LLMError as e:
            if state.status not in (Status.AWAITING_CONFIRMATION, Status.BOOKING):
                state.status = Status.IDLE
            return ctx.error(e.code, "The assistant is unavailable right now.", e.retryable)

    # ------------------------------------------------------------- handlers
    def _on_user_message(self, ctx: "_Ctx", msg: UserMessage) -> Outbound:
        s = ctx.state
        if s.status in (Status.AWAITING_TOOL_RESULT, Status.BOOKING):
            return ctx.error("busy", "Still working on the last step. Stop it or wait.")
        # Any new user message cancels a pending confirmation: the user must see a fresh
        # recommendation card before anything can be booked.
        gate.invalidate(s)
        s.recommended_option_id = None
        s.recommendation_reason = None
        s.status = Status.IDLE
        if msg.location:
            s.device_location = msg.location
        s.messages.append({"role": "user", "content": msg.text})
        return self._run(ctx)

    def _on_tool_result(self, ctx: "_Ctx", msg: ToolResult) -> Outbound:
        s = ctx.state
        pa = s.pending_action
        if pa is None or pa.action_id != msg.action_id:
            return ctx.error("unexpected_tool_result", "No action with that id is pending.")
        s.pending_action = None

        if pa.kind == "BOOK":
            return self._finish_booking(ctx, pa, msg)

        batch = s.batch
        assert batch is not None
        batch.results[pa.app.value] = msg.status
        kept: list[FareObservation] = []
        if msg.status == "ok":
            for ob in msg.observations:
                # Only accept what was asked for: the app we opened and the ride types we
                # asked about. Anything else is discarded, never passed on.
                if ob.app == pa.app and ob.ride_type in pa.ride_types:
                    kept.append(ob)
                    oid = f"opt_{len(s.options) + 1}"
                    s.options[oid] = Option(option_id=oid, round=s.round, observation=ob)
        if kept:
            ctx.hooks.record_observations(kept)

        if msg.status == "stopped_by_user":
            for app in batch.queue:
                batch.results[app.value] = "skipped"
            batch.queue.clear()
            self._close_batch(ctx)
            s.status = Status.IDLE
            text = "Stopped. Nothing was booked."
            s.messages.append({"role": "assistant", "content": text})
            return AssistantMessage(session_id=ctx.session_id, text=text)

        nxt = self._next_fare_action(ctx)
        if nxt is not None:
            return nxt
        return self._run(ctx)

    def _on_confirmation(self, ctx: "_Ctx", msg: Confirmation) -> Outbound:
        s = ctx.state
        if not msg.accept:
            if s.status != Status.AWAITING_CONFIRMATION:
                return ctx.error("no_pending_confirmation", "Nothing is awaiting confirmation.")
            gate.invalidate(s)
            s.status = Status.IDLE
            text = "Okay, I won't book it. Tell me what to change."
            s.messages.append({"role": "assistant", "content": text})
            return AssistantMessage(session_id=ctx.session_id, text=text)
        try:
            return gate.authorize_booking(s, ctx.session_id, msg, ctx.now)
        except gate.GateError as e:
            return ctx.error(e.code, e.message)

    def _finish_booking(self, ctx: "_Ctx", pa: PendingAction, msg: ToolResult) -> Outbound:
        s = ctx.state
        assert pa.option_id is not None
        option = s.options[pa.option_id]
        ob = option.observation
        name = f"{ob.app.value.capitalize()} {ob.ride_type.value}"
        if msg.status == "ok" and msg.booking and msg.booking.booked:
            s.status = Status.DONE
            s.booked_option_id = option.option_id
            sv = savings(option, list(s.options.values()))
            text = f"Booked: {name}." + (f" You saved ₹{sv.amount}." if sv else "")
            s.messages.append({"role": "assistant", "content": text})
            return AssistantMessage(session_id=ctx.session_id, text=text, savings=sv, done=True)
        s.status = Status.IDLE
        gate.invalidate(s)
        text = f"The booking in {ob.app.value.capitalize()} didn't go through. Nothing was booked."
        s.messages.append({"role": "assistant", "content": text})
        return AssistantMessage(session_id=ctx.session_id, text=text)

    # ------------------------------------------------------------ LLM loop
    def _run(self, ctx: "_Ctx") -> Outbound:
        s = ctx.state
        tools = tools_for(ctx.caps)
        allowed = {t["function"]["name"] for t in tools}
        for _ in range(self.settings.max_llm_steps_per_turn):
            msgs = [{"role": "system", "content": system_prompt(ctx.now.astimezone(IST), ctx.caps)}]
            turn = self.llm.complete(msgs + s.messages, tools)
            s.messages.append(turn.message)
            if not turn.tool_calls:
                s.status = Status.IDLE
                text = turn.text.strip() or "Sorry, could you say that again?"
                return AssistantMessage(session_id=ctx.session_id, text=text)
            terminal: Outbound | None = None
            for call in turn.tool_calls:
                if terminal is not None:
                    ctx.tool_reply(call, {"error": "not run: wait for the previous step"})
                    continue
                try:
                    if call.parse_error:
                        raise ToolError(call.parse_error)
                    if call.name not in allowed:
                        raise ToolError(f"unknown tool {call.name!r}")
                    if call.name == "request_fare_check":
                        terminal = self._start_fare_check(ctx, call)
                    elif call.name == "request_confirmation":
                        terminal = self._request_confirmation(ctx, call)
                    else:
                        ctx.tool_reply(call, self._server_tool(ctx, call))
                except (ToolError, ValidationError, ValueError) as e:
                    ctx.tool_reply(call, {"error": _short(e)})
            if terminal is not None:
                return terminal
        s.status = Status.IDLE
        return ctx.error("agent_step_limit", "I got stuck. Please try rephrasing.", True)

    # ---------------------------------------------------------- tools
    def _server_tool(self, ctx: "_Ctx", call: ToolCall) -> dict[str, Any]:
        s, a = ctx.state, call.arguments
        if call.name == "geocode_place":
            query = str(a.get("query", "")).strip()
            if not query:
                raise ToolError("query is required")
            if query.lower() in CURRENT_LOCATION:
                if s.device_location is None:
                    raise ToolError("current location not shared; ask the user where they are")
                place, city = s.device_location, "unknown"
            else:
                hit = geocode(query, a.get("city"))
                if hit is None:
                    raise ToolError(f"place not found: {query[:60]}")
                place, city = hit.place, hit.city
            pid = f"pl_{len(s.places) + 1}"
            s.places[pid] = ResolvedPlace(place_id=pid, city=city, place=place)
            # Coordinates stay server-side; the model only needs the id and label.
            return {"place_id": pid, "label": place.label, "city": city}

        if call.name == "rank_options":
            req = RankRequest.model_validate(a)
            night = req.late_night or is_late_night(ctx.now)
            req = req.model_copy(update={"late_night": night})
            options = s.current_options()
            if not options:
                raise ToolError("no fare options yet; call request_fare_check first")
            ranked = rank(options, req)
            return {
                "ranked": [
                    _option_json(r.option) | {"score": r.score}
                    for r in ranked
                    if r.excluded_reason is None
                ],
                "excluded": [
                    {"option_id": r.option.option_id, "reason": r.excluded_reason}
                    for r in ranked
                    if r.excluded_reason is not None
                ],
                "late_night_applied": night,
            }

        if call.name == "recommend_ride":
            oid = str(a.get("option_id", ""))
            if oid not in {o.option_id for o in s.current_options()}:
                raise ToolError(f"unknown option_id {oid[:40]!r}")
            s.recommended_option_id = oid
            s.recommendation_reason = str(a.get("reason", ""))[:200] or None
            return {"recorded": oid}

        if call.name == "ondc_search":
            return {"available": False, "reason": "ONDC is not live yet; use the ride apps."}

        raise ToolError(f"unknown tool {call.name!r}")

    def _start_fare_check(self, ctx: "_Ctx", call: ToolCall) -> Outbound | None:
        s, a = ctx.state, call.arguments
        if not ctx.caps.can_check_fares:
            raise ToolError("this device cannot check fares")
        pickup_id, drop_id = str(a.get("pickup_id", "")), str(a.get("drop_id", ""))
        if pickup_id not in s.places or drop_id not in s.places:
            raise ToolError("unknown place_id; call geocode_place first")
        if pickup_id == drop_id:
            raise ToolError("pickup and drop are the same place")
        apps = [App(x) for x in (a.get("apps") or [x.value for x in App])]
        ride_types = [RideType(x) for x in (a.get("ride_types") or [x.value for x in RideType])]
        s.round += 1
        s.trip = (pickup_id, drop_id)
        s.batch = FareCheckBatch(
            tool_call_id=call.id,
            pickup_id=pickup_id,
            drop_id=drop_id,
            ride_types=list(dict.fromkeys(ride_types)),
            queue=list(dict.fromkeys(apps)),
        )
        return self._next_fare_action(ctx)

    def _next_fare_action(self, ctx: "_Ctx") -> ActionRequest | None:
        """Send the next app in the batch to the phone, or close the batch when done."""
        s = ctx.state
        b = s.batch
        assert b is not None
        while b.queue:
            app = b.queue.pop(0)
            if not ctx.hooks.allow_fare_check():
                b.results[app.value] = "rate_limited"
                continue
            aid = s.next_action_id()
            s.pending_action = PendingAction(
                action_id=aid, kind="CHECK_FARES", app=app, ride_types=b.ride_types
            )
            s.status = Status.AWAITING_TOOL_RESULT
            return ActionRequest(
                session_id=ctx.session_id,
                action_id=aid,
                action=CheckFaresAction(
                    app=app,
                    pickup=s.places[b.pickup_id].place,
                    drop=s.places[b.drop_id].place,
                    ride_types=b.ride_types,
                ),
            )
        self._close_batch(ctx)
        return None

    def _close_batch(self, ctx: "_Ctx") -> None:
        s = ctx.state
        b = s.batch
        assert b is not None
        s.messages.append(
            {
                "role": "tool",
                "tool_call_id": b.tool_call_id,
                "content": json.dumps(
                    {
                        "apps": b.results,
                        "options": [_option_json(o) for o in s.current_options()],
                    }
                ),
            }
        )
        s.batch = None
        s.status = Status.IDLE

    def _request_confirmation(self, ctx: "_Ctx", call: ToolCall) -> Outbound:
        s = ctx.state
        oid = str(call.arguments.get("option_id", ""))
        if oid != s.recommended_option_id:
            raise ToolError("call recommend_ride with this option_id first")
        prompt = gate.issue_confirmation(s, oid, ctx.now, self.settings.confirmation_ttl_seconds)
        ctx.tool_reply(
            call, {"shown_to_user": True, "note": "Waiting for the user's tap. Nothing is booked."}
        )
        option = s.options[oid]
        current = s.current_options()
        return Recommendation(
            session_id=ctx.session_id,
            text=s.recommendation_reason or "Here's the best option I found.",
            recommended=view(option),
            options=[view(o) for o in sorted(current, key=lambda o: o.observation.fare_mid)],
            savings_if_booked=savings(option, current),
            confirmation=prompt,
        )


@dataclass
class _Ctx:
    session_id: str
    state: SessionState
    caps: Capabilities
    now: datetime
    hooks: Hooks

    def tool_reply(self, call: ToolCall, payload: dict[str, Any]) -> None:
        self.state.messages.append(
            {"role": "tool", "tool_call_id": call.id, "content": json.dumps(payload)}
        )

    def error(self, code: str, message: str, retryable: bool = False) -> ErrorResponse:
        return ErrorResponse(
            session_id=self.session_id, code=code, message=message, retryable=retryable
        )


def _option_json(o: Option) -> dict[str, Any]:
    ob = o.observation
    return {
        "option_id": o.option_id,
        "app": ob.app.value,
        "ride_type": ob.ride_type.value,
        "fare_min": ob.fare_min,
        "fare_max": ob.fare_max,
        "eta_minutes": ob.eta_minutes,
        "surge": ob.surge_flag,
    }


def _short(e: Exception) -> str:
    if isinstance(e, ValidationError):
        return "invalid arguments: " + "; ".join(
            f"{'.'.join(map(str, err['loc']))}: {err['msg']}" for err in e.errors()[:3]
        )
    return str(e)[:200]
