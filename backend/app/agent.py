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
from app.catalog import lookup
from app.config import Settings
from app.geocode import geocode
from app.llm import LLMClient, LLMError, ToolCall
from app.protocol import (
    ActionRequest,
    App,
    AssistantMessage,
    Capabilities,
    CheckFaresAction,
    ChoiceOption,
    Confirmation,
    ErrorResponse,
    FareObservation,
    Outbound,
    Place,
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
            if state.status not in (Status.AWAITING_CONFIRMATION, Status.HANDING_OFF):
                state.status = Status.IDLE
            return ctx.error(e.code, "The assistant is unavailable right now.", e.retryable)

    # ------------------------------------------------------------- handlers
    def _on_user_message(self, ctx: "_Ctx", msg: UserMessage) -> Outbound:
        s = ctx.state
        if s.status in (Status.AWAITING_TOOL_RESULT, Status.HANDING_OFF):
            return ctx.error("busy", "Still working on the last step. Stop it or wait.")
        # Any new user message cancels a pending card: the user must see a fresh card
        # before Hail opens any app for them.
        gate.invalidate(s)
        s.recommended_option_id = None
        s.recommendation_reason = None
        s.status = Status.IDLE
        if msg.location:
            s.device_location = msg.location
        if msg.available_apps is not None:
            s.available_apps = list(dict.fromkeys(msg.available_apps))
        s.messages.append({"role": "user", "content": msg.text})
        return self._run(ctx)

    def _on_tool_result(self, ctx: "_Ctx", msg: ToolResult) -> Outbound:
        s = ctx.state
        pa = s.pending_action
        if pa is None or pa.action_id != msg.action_id:
            return ctx.error("unexpected_tool_result", "No action with that id is pending.")
        s.pending_action = None

        if pa.kind == "OPEN_APP":
            return self._finish_handoff(ctx, pa, msg)

        batch = s.batch
        assert batch is not None
        batch.results[pa.app.value] = msg.status
        kept: list[FareObservation] = []
        if msg.status == "ok":
            for ob in msg.observations:
                # Only accept what was asked for: the app we opened and the ride types we
                # asked about. Anything else is discarded, never passed on.
                if ob.app == pa.app and ob.ride_type in pa.ride_types and _catalog_ok(ob):
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
            text = "Stopped."
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
                return ctx.error("no_pending_confirmation", "Nothing is awaiting your choice.")
            gate.invalidate(s)
            s.status = Status.IDLE
            text = "Okay. Tell me what to change."
            s.messages.append({"role": "assistant", "content": text})
            return AssistantMessage(session_id=ctx.session_id, text=text)
        try:
            return gate.authorize_handoff(s, ctx.session_id, msg, ctx.now)
        except gate.GateError as e:
            return ctx.error(e.code, e.message)

    def _finish_handoff(self, ctx: "_Ctx", pa: PendingAction, msg: ToolResult) -> Outbound:
        s = ctx.state
        assert pa.option_id is not None
        option = s.options[pa.option_id]
        ob = option.observation
        app_name = ob.app.value.capitalize()
        if msg.status == "ok" and msg.handoff and msg.handoff.opened:
            s.status = Status.DONE
            s.chosen_option_id = option.option_id
            sv = savings(option, list(s.options.values()))
            text = (
                f"Opened {app_name} with your trip. Pick {ob.ride_type.value} and tap Book there."
            )
            if sv:
                text += f" You save ₹{sv.amount}."
            s.messages.append({"role": "assistant", "content": text})
            return AssistantMessage(session_id=ctx.session_id, text=text, savings=sv, done=True)
        s.status = Status.IDLE
        gate.invalidate(s)
        text = f"I couldn't open {app_name}. Check it's installed and logged in, then ask again."
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
                    elif call.name == "present_options":
                        terminal = self._present_options(ctx, call)
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
                if hit is not None:
                    place, city = hit.place, hit.city
                else:
                    # Not in our list: pass the name through; the ride apps' own search finds it.
                    label = " ".join(query.split())[:80]
                    place, city = Place(label=label), "unknown"
            pid = f"pl_{len(s.places) + 1}"
            here = query.lower() in CURRENT_LOCATION
            s.places[pid] = ResolvedPlace(
                place_id=pid, city=city, place=place, is_device_location=here
            )
            # Coordinates stay server-side; the model only needs the id and label.
            return {
                "place_id": pid,
                "label": place.label,
                "city": city,
                "found_by": "map" if place.lat is not None else "ride_app_search",
            }

        if call.name == "rank_options":
            req = RankRequest.model_validate(a)
            night = req.late_night or is_late_night(ctx.now)
            req = req.model_copy(update={"late_night": night})
            options = s.current_options()
            if not options:
                raise ToolError("no fare options yet; call request_fare_check first")
            ranked = rank(options, req)
            s.ranked_option_ids = [r.option.option_id for r in ranked if r.excluded_reason is None]
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
        if s.available_apps is not None:  # only apps the user connected on this phone
            apps = [x for x in apps if x in s.available_apps]
        if not apps:
            raise ToolError("none of those apps are connected on this phone; tell the user")
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
                    pickup_is_current_location=s.places[b.pickup_id].is_device_location,
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

    def _present_options(self, ctx: "_Ctx", call: ToolCall) -> Outbound:
        """Show every eligible option (best-first) with one highlighted; the user picks."""
        s = ctx.state
        oid = str(call.arguments.get("recommended_option_id", ""))
        current = {o.option_id: o for o in s.current_options()}
        if oid not in current:
            raise ToolError(f"unknown option_id {oid[:40]!r}")
        ranked = [i for i in s.ranked_option_ids if i in current]
        order = ranked or sorted(current, key=lambda i: current[i].observation.fare_mid)
        if oid not in order:
            raise ToolError("that option was excluded by rank_options; recommend an eligible one")
        prompt = gate.issue_confirmation(s, order, ctx.now, self.settings.confirmation_ttl_seconds)
        reason = str(call.arguments.get("reason", ""))[:200].strip()
        ctx.tool_reply(
            call,
            {
                "shown_to_user": True,
                "note": "The user picks an option in the app. Nothing is booked.",
            },
        )
        opts = list(current.values())
        return Recommendation(
            session_id=ctx.session_id,
            text=reason or "Here are your options.",
            recommended_option_id=oid,
            options=[
                ChoiceOption(
                    **view(current[i]).model_dump(),
                    saves=(sv.amount if (sv := savings(current[i], opts)) else None),
                )
                for i in order
            ],
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


def _catalog_ok(ob: FareObservation) -> bool:
    """Product keys must be known for that app, not excluded, and match the ride type."""
    if ob.product is None:
        return True
    p = lookup(ob.app, ob.product)
    return p is not None and p.exclude is None and p.ride_type == ob.ride_type


def _option_json(o: Option) -> dict[str, Any]:
    ob = o.observation
    p = lookup(ob.app, ob.product)
    return {
        "option_id": o.option_id,
        "app": ob.app.value,
        "product": p.name if p else None,
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
