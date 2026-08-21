"""Idempotency-key contract for POST /payments."""

import uuid

from tests.helpers import balance, create_account, unique_key


def test_exact_retry_replays_stored_response_without_reprocessing(client):
    a = create_account(client, "Alice", 10_000)
    b = create_account(client, "Bob", 0)
    key = unique_key()
    payload = {"from_account_id": a["id"], "to_account_id": b["id"], "amount_cents": 2_500}

    r1 = client.post("/payments", json=payload, headers={"Idempotency-Key": key})
    r2 = client.post("/payments", json=payload, headers={"Idempotency-Key": key})

    assert r1.status_code == 201, r1.text
    assert r1.json() == r2.json(), "retry must return the exact original response"
    # the transfer must only have happened once
    assert balance(client, a["id"]) == 7_500
    assert balance(client, b["id"]) == 2_500


def test_same_key_different_payload_is_rejected(client):
    a = create_account(client, "Alice", 10_000)
    b = create_account(client, "Bob", 0)
    key = unique_key()
    payload = {"from_account_id": a["id"], "to_account_id": b["id"], "amount_cents": 1_000}
    client.post("/payments", json=payload, headers={"Idempotency-Key": key})

    r = client.post(
        "/payments", json=dict(payload, amount_cents=1), headers={"Idempotency-Key": key}
    )
    assert r.status_code == 422


def test_missing_idempotency_key_header_is_rejected(client):
    a = create_account(client, "Alice", 10_000)
    b = create_account(client, "Bob", 0)
    r = client.post(
        "/payments",
        json={"from_account_id": a["id"], "to_account_id": b["id"], "amount_cents": 100},
    )
    assert r.status_code == 422


def test_insufficient_funds_creates_an_auditable_failed_transaction(client):
    a = create_account(client, "Alice", 100)
    b = create_account(client, "Bob", 0)
    r = client.post(
        "/payments",
        json={"from_account_id": a["id"], "to_account_id": b["id"], "amount_cents": 999},
        headers={"Idempotency-Key": unique_key()},
    )
    assert r.status_code == 402
    body = r.json()
    assert body["status"] == "failed"

    # the failure is a real, fetchable record -- not just an error message
    r2 = client.get(f"/payments/{body['id']}")
    assert r2.status_code == 200
    assert r2.json()["status"] == "failed"
    assert balance(client, a["id"]) == 100, "a failed payment must not move money"


def test_same_account_transfer_is_rejected(client):
    a = create_account(client, "Alice", 1_000)
    r = client.post(
        "/payments",
        json={"from_account_id": a["id"], "to_account_id": a["id"], "amount_cents": 100},
        headers={"Idempotency-Key": unique_key()},
    )
    assert r.status_code == 422


def test_unknown_account_returns_404(client):
    a = create_account(client, "Alice", 1_000)
    r = client.post(
        "/payments",
        json={
            "from_account_id": a["id"],
            "to_account_id": str(uuid.uuid4()),
            "amount_cents": 100,
        },
        headers={"Idempotency-Key": unique_key()},
    )
    assert r.status_code == 404
