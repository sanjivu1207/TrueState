"""Test setup.

These are integration tests: they run the real FastAPI app against a real
Postgres database — never SQLite. Phase 2 development found that SQLite
silently no-ops `SELECT ... FOR UPDATE`, which made a real lost-update race
look like it passed. That's exactly the class of bug these tests exist to
catch, so testing against anything but Postgres would defeat the point.

Start Postgres first: `docker compose up -d db`
Then run: `pytest`
"""

import os

# Must be set before any `app.*` import — app.config.Settings() reads the
# environment once, at import time. Defaults to the port docker-compose
# exposes; override with a real DATABASE_URL env var if needed.
os.environ.setdefault(
    "DATABASE_URL", "postgresql+psycopg2://payments:payments@localhost:5432/payments"
)

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import text
from sqlalchemy.exc import OperationalError

import app.main as main_module
from app.config import settings
from app.database import Base, engine


@pytest.fixture(scope="session", autouse=True)
def _database():
    try:
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
    except OperationalError:
        pytest.exit(
            f"Could not reach Postgres at {settings.database_url}. "
            "Start it first: `docker compose up -d db`",
            returncode=1,
        )
    Base.metadata.create_all(bind=engine)
    yield


@pytest.fixture(autouse=True)
def _clean_tables():
    """Truncate all tables before every test so tests don't see leftover
    rows from previous ones, without paying for a fresh container per test."""
    with engine.begin() as conn:
        conn.execute(
            text("TRUNCATE accounts, transactions, idempotency_records RESTART IDENTITY CASCADE")
        )
    yield


@pytest.fixture
def client():
    with TestClient(main_module.app) as c:
        yield c
