import uuid


def create_account(client, name="Test", balance_cents=0):
    r = client.post(
        "/accounts", json={"owner_name": name, "starting_balance_cents": balance_cents}
    )
    assert r.status_code == 201, r.text
    return r.json()


def balance(client, account_id) -> int:
    r = client.get(f"/accounts/{account_id}/balance")
    assert r.status_code == 200, r.text
    return r.json()["balance_cents"]


def unique_key() -> str:
    return str(uuid.uuid4())


def authorize(client, from_account, to_account, amount_cents):
    r = client.post(
        "/payments",
        json={
            "from_account_id": from_account["id"],
            "to_account_id": to_account["id"],
            "amount_cents": amount_cents,
        },
        headers={"Idempotency-Key": unique_key()},
    )
    assert r.status_code == 201, r.text
    return r.json()
