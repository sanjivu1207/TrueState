from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Application configuration, loaded from environment variables / .env."""

    database_url: str = (
        "postgresql+psycopg2://payments:payments@localhost:5432/payments"
    )

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()
