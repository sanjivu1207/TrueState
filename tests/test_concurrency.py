"""The two tests that actually prove the core claim of this project: exactly-
once effects under concurrent, at-least-once delivery.

Run only against real Postgres (see conftest.py) — SQLite silently ignores
`SELECT ... FOR UPDATE`, which makes the overdraft test below pass for the
wrong reason (or not catch a real lost-update bug at all).
"""

from concurrent.futures import ThreadPoolExecutor

from sqlalchemy import select

from app.database import SessionLocal
from app.models import Transaction
from tests.helpers import balance, create_account, unique_key


def test_50_concurrent_identical_requests_create_exactly_one_transaction(client):
    payer = create_account(client, "Payer", 10_000)
    payee = create_account(client, "Payee", 0)
    key = unique_key()
    payload = {
        "from_account_id": payer["id"],
        "to_account_id": payee["id"],
        "amount_cents": 1_000,
    }

    def fire(_):
        return client.post("/payments", json=payload, headers={"Idempotency-Key": key})

    with ThreadPoolExecutor(max_workers=50) as pool:
        results = list(pool.map(fire, range(50)))

    statuses = [r.status_code for r in results]
    assert all(s == 201 for s in statuses), statuses

    bodies = [r.json() for r in results]
    assert len({str(b) for b in bodies}) == 1, "all 50 responses must be byte-identical"

    with SessionLocal() as db:
        rows = db.execute(
            select(Transaction).where(Transaction.idempotency_key == key)
        ).scalars().all()
    assert len(rows) == 1, "exactly one transaction record must be created, not 50"

    # the transfer happened exactly once, not 50 times
    assert balance(client, payer["id"]) == 9_000
    assert balance(client, payee["id"]) == 1_000


def test_concurrent_overdraft_race_never_takes_balance_negative(client):
    payer = create_account(client, "Payer", 10_000)
    payee = create_account(client, "Payee", 0)

    # 20 distinct 1000-cent debits (different idempotency keys, so each is a
    # genuinely separate payment) fired at once against an account that can
    # only cover 10 of them.
    def fire(_):
        return client.post(
            "/payments",
            json={
                "from_account_id": payer["id"],
                "to_account_id": payee["id"],
                "amount_cents": 1_000,
            },
            headers={"Idempotency-Key": unique_key()},
        )

    with ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(fire, range(20)))

    statuses = [r.status_code for r in results]
    successes = statuses.count(201)
    failures = statuses.count(402)
    assert successes + failures == 20, statuses

    payer_balance = balance(client, payer["id"])
    payee_balance = balance(client, payee["id"])

    assert payer_balance >= 0, "balance must never go negative under concurrent load"
    assert payer_balance + payee_balance == 10_000, "money must be conserved, not lost or duplicated"
    # 10,000 cents / 1,000 cents each = exactly 10 can succeed
    assert successes == 10, f"expected exactly 10 successes, got {successes}"
    assert payer_balance == 0
