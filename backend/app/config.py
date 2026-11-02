from functools import lru_cache
from typing import Literal

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    openrouter_api_key: SecretStr | None = None
    openrouter_base_url: str = "https://openrouter.ai/api/v1"
    gemini_model: str = "google/gemini-3.7-flash"
    hail_llm: Literal["openrouter", "fake"] = "openrouter"
    llm_timeout_seconds: float = 30.0

    database_url: str = "sqlite:///./hail.db"

    confirmation_ttl_seconds: int = 90
    fare_checks_per_hour: int = 30
    max_llm_steps_per_turn: int = 8
    # Prices older than this (by captured_at) are rejected: only live readings are shown.
    max_price_age_seconds: int = 180


@lru_cache
def get_settings() -> Settings:
    return Settings()
