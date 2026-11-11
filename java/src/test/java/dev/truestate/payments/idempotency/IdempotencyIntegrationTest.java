package dev.truestate.payments.idempotency;

import static dev.truestate.payments.support.Api.payment;
import static dev.truestate.payments.support.Api.uniqueKey;
import static org.assertj.core.api.Assertions.assertThat;

import dev.truestate.payments.support.Api.Res;
import dev.truestate.payments.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Idempotency behaviour that needs a real database to observe. */
class IdempotencyIntegrationTest extends PostgresIntegrationTest {

    @Test
    void completedKeyStoresTheExactResponseBody() {
        String a = api.createAccount("Alice", 10_000);
        String b = api.createAccount("Bob", 0);
        String key = uniqueKey();

        Res r = api.post("/payments", payment(a, b, 500), key);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status::text AS status, response_status_code, response_body::text AS body"
                        + " FROM idempotency_records WHERE idempotency_key = ?",
                key);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("response_status_code")).isEqualTo(201);
        assertThat(row.get("body")).isEqualTo(r.body());
    }

    /**
     * A request that claimed a key and then died (crash, killed pod) leaves it
     * PROCESSING forever. After 30s a retry takes it over and processes once.
     */
    @Test
    void staleProcessingKeyIsReclaimedAndProcessedOnce() {
        String a = api.createAccount("Alice", 10_000);
        String b = api.createAccount("Bob", 0);
        String key = uniqueKey();
        Map<String, Object> body = payment(a, b, 700);
        String hash = RequestHasher.hash(Map.of(
                "from_account_id", a, "to_account_id", b, "amount_cents", 700L));
        jdbc.update(
                "INSERT INTO idempotency_records (idempotency_key, request_hash, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'PROCESSING', now() - interval '60 seconds', now() - interval '60 seconds')",
                key, hash);

        Res r = api.post("/payments", body, key);

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(transactionRowsForKey(key)).isEqualTo(1);
        assertThat(api.balance(a)).isEqualTo(9_300);
        assertThat(api.post("/payments", body, key).body()).as("and later retries replay it").isEqualTo(r.body());
    }

    /**
     * Records written by the Python service store JSON with spaces after
     * separators. Replays come back compact, field order preserved.
     */
    @Test
    void replayOfARecordWrittenByThePythonServiceIsCompactJson() {
        String key = uniqueKey();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String hash = RequestHasher.hash(Map.of("from_account_id", a, "to_account_id", b, "amount_cents", 5L));
        jdbc.update(
                "INSERT INTO idempotency_records (idempotency_key, request_hash, status, response_status_code,"
                        + " response_body, created_at, updated_at)"
                        + " VALUES (?, ?, 'COMPLETED', 404, ?::json, now(), now())",
                key, hash, "{\"detail\": \"from_account_id or to_account_id not found\"}");

        Res r = api.post("/payments", payment(a, b, 5), key);

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body()).isEqualTo("{\"detail\":\"from_account_id or to_account_id not found\"}");
    }

    @Test
    void businessErrorsAreStoredAgainstTheKey() {
        String a = api.createAccount("Alice", 1_000);
        String key = uniqueKey();

        Res first = api.post("/payments", payment(a, a, 5), key);
        Res retry = api.post("/payments", payment(a, a, 5), key);

        assertThat(first.status()).isEqualTo(422);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(jdbc.queryForObject(
                        "SELECT status::text FROM idempotency_records WHERE idempotency_key = ?", String.class, key))
                .isEqualTo("COMPLETED");
    }
}
