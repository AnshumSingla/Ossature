"""Runtime configuration, loaded from environment / .env."""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    agent_port: int = 8000

    openai_api_key: str = ""

    # Model tiers (PRD 8): cheap model for high-frequency Interview/Summary work,
    # stronger models for the once-per-session Council and the on-submit Review.
    llm_model_fast: str = "gpt-4o-mini"
    llm_model_council_generator: str = "gpt-4o"
    llm_model_council_critic: str = "gpt-4o"
    llm_model_review: str = "gpt-4o"

    langchain_tracing_v2: bool = False
    langchain_project: str = "ossature-interviewer"

    @property
    def stub_mode(self) -> bool:
        """No API key -> run the graph with deterministic placeholder output."""
        return not self.openai_api_key


@lru_cache
def get_settings() -> Settings:
    return Settings()
