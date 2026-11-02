"""HTTP API.

POST   /v1/devices                         register a client, get a bearer token (once)
POST   /v1/sessions/{session_id}/messages  one inbound message -> one outbound response
GET    /v1/config/readers                  ride-app reader selectors (remote-fixable)
DELETE /v1/me                              delete everything stored for this device
GET    /healthz
"""

import json
import logging
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated, Any

from fastapi import Body, Depends, FastAPI, Header, HTTPException, Response
from fastapi import Path as PathParam
from fastapi.responses import JSONResponse

from app.agent import Agent, Hooks
from app.config import Settings, get_settings
from app.llm import FakeLLM, LLMClient, OpenRouterClient
from app.protocol import (
    Capabilities,
    DeviceCredentials,
    DeviceRegistration,
    Inbound,
    Outbound,
)
from app.store import Conflict, DeviceRow, Store

log = logging.getLogger("hail")
READERS_CONFIG = Path(__file__).parent / "readers_config.json"
SessionId = Annotated[str, PathParam(pattern=r"^[A-Za-z0-9_-]{8,64}$")]


def build_llm(settings: Settings) -> LLMClient:
    if settings.hail_llm == "fake":
        return FakeLLM()
    if settings.openrouter_api_key is None:
        raise RuntimeError("OPENROUTER_API_KEY is not set (or set HAIL_LLM=fake)")
    return OpenRouterClient(
        settings.openrouter_api_key.get_secret_value(),
        settings.gemini_model,
        settings.openrouter_base_url,
        settings.llm_timeout_seconds,
    )


def create_app(
    settings: Settings | None = None,
    llm: LLMClient | None = None,
    clock: Callable[[], datetime] = lambda: datetime.now(UTC),
) -> FastAPI:
    settings = settings or get_settings()
    store = Store(settings.database_url)
    agent = Agent(llm or build_llm(settings), settings)
    app = FastAPI(title="Hail", version="0.1.0")
    app.state.store = store

    def device(authorization: Annotated[str | None, Header()] = None) -> DeviceRow:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, "missing bearer token")
        row = store.device_by_token(authorization.removeprefix("Bearer ").strip())
        if row is None:
            raise HTTPException(401, "invalid token")
        return row

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/v1/devices", status_code=201)
    def register(body: DeviceRegistration) -> DeviceCredentials:
        device_id, token = store.register_device(body.platform, body.capabilities, clock())
        return DeviceCredentials(device_id=device_id, token=token)

    @app.get("/v1/config/readers")
    def readers_config() -> dict[str, Any]:
        return json.loads(READERS_CONFIG.read_text())

    @app.delete("/v1/me", status_code=204)
    def delete_me(dev: Annotated[DeviceRow, Depends(device)]) -> Response:
        store.delete_device_data(dev.id)
        log.info("deleted all data for a device")
        return Response(status_code=204)

    @app.post("/v1/sessions/{session_id}/messages")
    def post_message(
        session_id: SessionId,
        msg: Annotated[Inbound, Body()],
        dev: Annotated[DeviceRow, Depends(device)],
    ) -> Outbound:
        now = clock()
        row, state = store.load_session(session_id)
        if row is None:
            row = store.create_session(session_id, dev.id, now)
            _, state = store.load_session(session_id)
        elif row.device_id != dev.id:
            raise HTTPException(404, "session not found")
        assert state is not None
        hooks = Hooks(
            allow_fare_check=lambda: store.try_consume_fare_check(
                dev.id, now, settings.fare_checks_per_hour
            ),
            record_observations=lambda obs: store.record_observations(session_id, dev.id, obs),
        )
        caps = Capabilities.model_validate(dev.capabilities)
        out = agent.handle(session_id, state, caps, msg, now, hooks)
        try:
            store.save_session(row, state, now)
        except Conflict:
            return JSONResponse(
                status_code=409,
                content={
                    "type": "error",
                    "session_id": session_id,
                    "code": "conflict",
                    "message": "session changed concurrently; resend",
                    "retryable": True,
                },
            )
        # Log shape only: never message text, coordinates or tokens.
        log.info(
            "session=%s in=%s out=%s status=%s", session_id, msg.type, out.type, state.status.value
        )
        return out

    return app


def app_factory() -> FastAPI:
    logging.basicConfig(level=logging.INFO)
    return create_app()
