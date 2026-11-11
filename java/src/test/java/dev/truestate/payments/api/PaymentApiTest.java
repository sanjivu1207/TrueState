package dev.truestate.payments.api;

import static dev.truestate.payments.support.Api.payment;
import static dev.truestate.payments.support.Api.uniqueKey;
import static org.assertj.core.api.Assertions.assertThat;

import dev.truestate.payments.support.Api.Res;
import dev.truestate.payments.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Idempotency-key contract for POST /payments. Port of tests/test_payments.py. */
class PaymentApiTest extends PostgresIntegrationTest {

    @Test
    void exactRetryReplaysStoredResponseWithoutReprocessing() {
        String a = api.createAccount("Alice", 10_000);
        String b = api.createAccount("Bob", 0);
        String key = uniqueKey();
        Map<String, Object> body = payment(a, b, 2_500);

        Res r1 = api.post("/payments", body, key);
        Res r2 = api.post("/payments", body, key);

        assertThat(r1.status()).as(r1.body()).isEqualTo(201);
        assertThat(r2.status()).isEqualTo(201);
        assertThat(r2.body()).as("retry returns the exact original bytes").isEqualTo(r1.body());
        assertThat(api.balance(a)).isEqualTo(7_500);
        assertThat(api.balance(b)).isEqualTo(2_500);
        assertThat(transactionRowsForKey(key)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentPayloadIsRejected() {
        String a = api.createAccount("Alice", 10_000);
        String b = api.createAccount("Bob", 0);
        String key = uniqueKey();
        api.post("/payments", payment(a, b, 1_000), key);

        Res r = api.post("/payments", payment(a, b, 1), key);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json().get("detail").asText())
                .isEqualTo("Idempotency-Key was already used with a different request payload");
        assertThat(api.balance(a)).as("the smuggled payment did not run").isEqualTo(9_000);
    }

    @Test
    void missingIdempotencyKeyHeaderIsRejected() {
        String a = api.createAccount("Alice", 10_000);
        String b = api.createAccount("Bob", 0);

        Res r = api.post("/payments", payment(a, b, 100));

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json().get("detail").get(0).get("loc").toString()).isEqualTo("[\"header\",\"Idempotency-Key\"]");
    }

    @Test
    void insufficientFundsCreatesAnAuditableFailedTransaction() {
        String a = api.createAccount("Alice", 100);
        String b = api.createAccount("Bob", 0);

        Res r = api.pay(a, b, 999);

        assertThat(r.status()).isEqualTo(402);
        assertThat(r.json().get("status").asText()).isEqualTo("failed");
        assertThat(r.json().get("detail").asText()).isEqualTo("insufficient funds");

        // the failure is a real, fetchable record, not just an error message
        Res fetched = api.get("/payments/" + r.json().get("id").asText());
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.json().get("status").asText()).isEqualTo("failed");
        assertThat(api.balance(a)).as("a failed payment must not move money").isEqualTo(100);
    }

    @Test
    void insufficientFundsIsReplayedNotRetried() {
        String a = api.createAccount("Alice", 100);
        String b = api.createAccount("Bob", 0);
        String key = uniqueKey();
        Res first = api.post("/payments", payment(a, b, 999), key);

        // Funds arrive, then the client retries with the same key.
        jdbc.update("UPDATE accounts SET balance_cents = 10000 WHERE id = ?::uuid", a);
        Res retry = api.post("/payments", payment(a, b, 999), key);

        assertThat(retry.status()).isEqualTo(402);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(api.balance(a)).as("the stored outcome is final for this key").isEqualTo(10_000);
    }

    @Test
    void sameAccountTransferIsRejected() {
        String a = api.createAccount("Alice", 1_000);

        Res r = api.pay(a, a, 100);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json().get("detail").asText()).isEqualTo("from_account_id and to_account_id must differ");
    }

    @Test
    void unknownAccountReturns404() {
        String a = api.createAccount("Alice", 1_000);

        Res r = api.pay(a, UUID.randomUUID().toString(), 100);

        assertThat(r.status()).isEqualTo(404);
        assertThat(api.balance(a)).isEqualTo(1_000);
    }

    @Test
    void nonPositiveAmountIsRejectedBeforeTouchingTheKey() {
        String a = api.createAccount("Alice", 1_000);
        String b = api.createAccount("Bob", 0);
        String key = uniqueKey();

        Res r = api.post("/payments", payment(a, b, 0), key);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json().get("detail").get(0).get("type").asText()).isEqualTo("greater_than");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?", Integer.class, key))
                .as("invalid requests don't burn the key")
                .isZero();
    }

    @Test
    void accountValidationErrorsUsePydanticShapeInFieldOrder() {
        Res r = api.post("/accounts", Map.of("owner_name", "", "starting_balance_cents", -1));

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body())
                .isEqualTo("{\"detail\":["
                        + "{\"type\":\"string_too_short\",\"loc\":[\"body\",\"owner_name\"],"
                        + "\"msg\":\"String should have at least 1 character\",\"input\":\"\",\"ctx\":{\"min_length\":1}},"
                        + "{\"type\":\"greater_than_equal\",\"loc\":[\"body\",\"starting_balance_cents\"],"
                        + "\"msg\":\"Input should be greater than or equal to 0\",\"input\":-1,\"ctx\":{\"ge\":0}}]}");
    }

    @Test
    void responseTimestampsMatchTheDatabase() {
        String a = api.createAccount("Alice", 1_000);
        String b = api.createAccount("Bob", 0);
        Res created = api.pay(a, b, 100);

        Res fetched = api.get("/payments/" + created.json().get("id").asText());

        assertThat(fetched.body()).as("what we returned is exactly what we stored").isEqualTo(created.body());
    }
}
