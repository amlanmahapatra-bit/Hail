"""HARD SAFETY RULE: the confirmation gate.

A BOOK action can only be produced by authorize_booking(), and only when:
  1. the session showed a recommendation (issue_confirmation was called) for this option,
  2. the client sent a typed Confirmation for that exact option_id,
  3. carrying the single-use token from that recommendation,
  4. after it was shown and before it expired.

The token is random, generated here, stored only as a hash, and never placed in the LLM
context. So neither the model nor text read from another app's screen can produce one.
Any new user message invalidates a pending confirmation (see agent.py).
"""

import hashlib
import hmac
import secrets
from datetime import datetime, timedelta

from app.protocol import BookAction, BookingRequest, Confirmation, ConfirmationPrompt
from app.state import PendingAction, PendingConfirmation, SessionState, Status


class GateError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def issue_confirmation(
    state: SessionState, option_id: str, now: datetime, ttl_seconds: int
) -> ConfirmationPrompt:
    if option_id not in state.options:
        raise GateError("unknown_option", "option does not exist")
    token = secrets.token_urlsafe(24)
    state.confirmation = PendingConfirmation(
        option_id=option_id,
        token_hash=_hash(token),
        shown_at=now,
        expires_at=now + timedelta(seconds=ttl_seconds),
    )
    state.status = Status.AWAITING_CONFIRMATION
    return ConfirmationPrompt(
        option_id=option_id, token=token, expires_at=state.confirmation.expires_at
    )


def invalidate(state: SessionState) -> None:
    state.confirmation = None


def authorize_booking(
    state: SessionState, session_id: str, conf: Confirmation, now: datetime
) -> BookingRequest:
    pending = state.confirmation
    if state.status != Status.AWAITING_CONFIRMATION or pending is None:
        raise GateError("no_pending_confirmation", "nothing is awaiting confirmation")
    if pending.used:
        raise GateError("confirmation_used", "this confirmation was already used")
    if not hmac.compare_digest(pending.token_hash, _hash(conf.token)):
        raise GateError("bad_token", "confirmation token does not match")
    if conf.option_id != pending.option_id:
        raise GateError("option_mismatch", "confirmation is for a different option")
    if now < pending.shown_at:
        raise GateError("confirmation_too_early", "confirmation predates recommendation")
    if now > pending.expires_at:
        state.confirmation = None
        state.status = Status.IDLE
        raise GateError("confirmation_expired", "confirmation expired; fares may have changed")
    option = state.options.get(conf.option_id)
    if option is None or state.trip is None:
        raise GateError("unknown_option", "option does not exist")

    pending.used = True
    pickup, drop = (state.places[p].place for p in state.trip)
    action_id = state.next_action_id()
    state.pending_action = PendingAction(
        action_id=action_id, kind="BOOK", app=option.observation.app, option_id=option.option_id
    )
    state.status = Status.BOOKING
    return BookingRequest(
        session_id=session_id,
        action_id=action_id,
        action=BookAction(
            app=option.observation.app,
            option_id=option.option_id,
            ride_type=option.observation.ride_type,
            pickup=pickup,
            drop=drop,
        ),
    )
