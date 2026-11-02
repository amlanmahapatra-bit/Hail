"""HARD SAFETY RULE: the confirmation gate.

Hail never books. After a fare check it shows the user every option, and the user taps one.
Only that tap makes Hail open the chosen ride app (OPEN_APP), with the trip filled in, so
the user can book it themselves. An OPEN_APP action can only be produced by
authorize_handoff(), and only when:
  1. the session showed a recommendation card (issue_confirmation was called),
  2. the client sent a typed Confirmation for an option_id that was ON that card,
  3. carrying the single-use token from that card,
  4. after it was shown and before it expired.

The token is random, generated here, stored only as a hash, and never placed in the LLM
context. So neither the model nor text read from another app's screen can produce one.
Any new user message invalidates a pending card (see agent.py).
"""

import hashlib
import hmac
import secrets
from datetime import datetime, timedelta

from app.protocol import Confirmation, ConfirmationPrompt, HandoffRequest, OpenAppAction
from app.state import PendingAction, PendingConfirmation, SessionState, Status


class GateError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def issue_confirmation(
    state: SessionState, option_ids: list[str], now: datetime, ttl_seconds: int
) -> ConfirmationPrompt:
    if not option_ids or any(o not in state.options for o in option_ids):
        raise GateError("unknown_option", "option does not exist")
    token = secrets.token_urlsafe(24)
    state.confirmation = PendingConfirmation(
        option_ids=list(option_ids),
        token_hash=_hash(token),
        shown_at=now,
        expires_at=now + timedelta(seconds=ttl_seconds),
    )
    state.status = Status.AWAITING_CONFIRMATION
    return ConfirmationPrompt(token=token, expires_at=state.confirmation.expires_at)


def invalidate(state: SessionState) -> None:
    state.confirmation = None


def authorize_handoff(
    state: SessionState, session_id: str, conf: Confirmation, now: datetime
) -> HandoffRequest:
    pending = state.confirmation
    if state.status != Status.AWAITING_CONFIRMATION or pending is None:
        raise GateError("no_pending_confirmation", "nothing is awaiting the user's choice")
    if pending.used:
        raise GateError("confirmation_used", "this choice was already made")
    if not hmac.compare_digest(pending.token_hash, _hash(conf.token)):
        raise GateError("bad_token", "confirmation token does not match")
    if conf.option_id not in pending.option_ids:
        raise GateError("option_mismatch", "that option was not on the card")
    if now < pending.shown_at:
        raise GateError("confirmation_too_early", "choice predates the card")
    if now > pending.expires_at:
        state.confirmation = None
        state.status = Status.IDLE
        raise GateError("confirmation_expired", "fares may have changed; check again")
    option = state.options.get(conf.option_id)
    if option is None or state.trip is None:
        raise GateError("unknown_option", "option does not exist")

    pending.used = True
    pickup, drop = (state.places[p].place for p in state.trip)
    action_id = state.next_action_id()
    state.pending_action = PendingAction(
        action_id=action_id,
        kind="OPEN_APP",
        app=option.observation.app,
        option_id=option.option_id,
    )
    state.status = Status.HANDING_OFF
    return HandoffRequest(
        session_id=session_id,
        action_id=action_id,
        action=OpenAppAction(
            app=option.observation.app,
            option_id=option.option_id,
            ride_type=option.observation.ride_type,
            pickup=pickup,
            drop=drop,
        ),
    )
