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

    # Same physical Postgres instance the backend uses (docker-compose `postgres` service),
    # not part of the backend's Flyway-managed schema - the agent owns and creates its own
    # table here for session-state durability (see app/store.py). Naming mirrors the
    # backend's .env.example so one .env can be shared/copied between the two services.
    db_host: str = "127.0.0.1"  # literal IP, not "localhost" - see app/store.py
    db_port: int = 5432
    postgres_db: str = "ossature"
    postgres_user: str = "ossature"
    postgres_password: str = "ossature"

    @property
    def stub_mode(self) -> bool:
        """No API key -> run the graph with deterministic placeholder output."""
        return not self.openai_api_key

    @property
    def database_url(self) -> str:
        return (
            f"postgresql+psycopg://{self.postgres_user}:{self.postgres_password}"
            f"@{self.db_host}:{self.db_port}/{self.postgres_db}"
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()
