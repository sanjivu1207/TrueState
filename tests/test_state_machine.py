"""Transaction state machine: capture/refund and illegal transitions."""

import uuid

from tests.helpers import authorize, balance, create_account, unique_key


def test_authorize_capture_refund_happy_path(client):
    a = create_account(client, "Alice", 10_000)
    b = create_account(client, "Bob", 0)
    txn = authorize(client, a, b, 3_000)
    assert txn["status"] == "authorized"

    r = client.post(f"/payments/{txn['id']}/capture")
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "captured"
    # capture is a pure status change here -- funds already moved at authorization
    assert balance(client, a["id"]) == 7_000
    assert balance(client, b["id"]) == 3_000

    r = client.post(f"/payments/{txn['id']}/refund")
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "refunded"
    assert balance(client, a["id"]) == 10_000
    assert balance(client, b["id"]) == 0


def test_capture_after_refund_is_rejected(client):
    a = create_account(client, "Alice", 10_000)
    b = create_account(client, "Bob", 0)
    txn = authorize(client, a, b, 1_000)
    client.post(f"/payments/{txn['id']}/capture")
    client.post(f"/payments/{txn['id']}/refund")

    r = client.post(f"/payments/{txn['id']}/capture")
    assert r.status_code == 409


def test_double_refund_is_rejected(client):
    a = create_account(client, "Alice", 10_000)
    b = create_account(client, "Bob", 0)
    txn = authorize(client, a, b, 1_000)
    client.post(f"/payments/{txn['id']}/capture")
    client.post(f"/payments/{txn['id']}/refund")

    r = client.post(f"/payments/{txn['id']}/refund")
    assert r.status_code == 409


def test_refund_without_capture_is_rejected(client):
    """PENDING/AUTHORIZED can't jump straight to REFUNDED -- only
    CAPTURED/SETTLED can."""
    a = create_account(client, "Alice", 10_000)
    b = create_account(client, "Bob", 0)
    txn = authorize(client, a, b, 1_000)

    r = client.post(f"/payments/{txn['id']}/refund")
    assert r.status_code == 409
    assert balance(client, b["id"]) == 1_000, "rejected refund must not move money"


def test_refund_blocked_when_receiver_no_longer_has_the_funds(client):
    a = create_account(client, "Carol", 10_000)
    b = create_account(client, "Dave", 0)
    txn = authorize(client, a, b, 4_000)
    client.post(f"/payments/{txn['id']}/capture")

    # Dave spends the funds onward before the refund is attempted
    client.post(
        "/payments",
        json={"from_account_id": b["id"], "to_account_id": a["id"], "amount_cents": 4_000},
        headers={"Idempotency-Key": unique_key()},
    )
    assert balance(client, b["id"]) == 0

    r = client.post(f"/payments/{txn['id']}/refund")
    assert r.status_code == 409

    # must not have silently flipped to refunded despite the rejection
    r2 = client.get(f"/payments/{txn['id']}")
    assert r2.json()["status"] == "captured"


def test_capture_unknown_payment_returns_404(client):
    r = client.post(f"/payments/{uuid.uuid4()}/capture")
    assert r.status_code == 404


def test_refund_unknown_payment_returns_404(client):
    r = client.post(f"/payments/{uuid.uuid4()}/refund")
    assert r.status_code == 404
