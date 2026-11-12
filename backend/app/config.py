from functools import lru_cache
from typing import Literal

from pydantic import AliasChoices, Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    openrouter_api_key: SecretStr | None = None
    openrouter_base_url: str = "https://openrouter.ai/api/v1"
    gemini_model: str = "google/gemini-3.7-flash"
    hail_llm: Literal["openrouter", "fake"] = "openrouter"
    llm_timeout_seconds: float = 30.0

    # Local: SQLite. Hosted: Supabase Postgres. Vercel's Supabase integration sets POSTGRES_URL.
    database_url: str = Field(
        "sqlite:///./hail.db", validation_alias=AliasChoices("DATABASE_URL", "POSTGRES_URL")
    )

    confirmation_ttl_seconds: int = 90
    fare_checks_per_hour: int = 30
    max_llm_steps_per_turn: int = 8
    # Each app's prices must reach the server within this many seconds of being read on the
    # phone, or they are rejected: only live readings are shown.
    max_price_age_seconds: int = 60
    # Invite-only beta: new devices must bring a valid invite code. Off for local dev and tests;
    # turn on in production (REQUIRE_INVITE=true).
    require_invite: bool = False
    invite_requests_per_day: int = 200


@lru_cache
def get_settings() -> Settings:
    return Settings()
