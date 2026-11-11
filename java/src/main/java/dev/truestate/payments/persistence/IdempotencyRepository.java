package dev.truestate.payments.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    public enum Status {
        PROCESSING,
        COMPLETED
    }

    public record IdempotencyRecord(
            String key,
            String requestHash,
            Status status,
            Integer responseStatusCode,
            String responseBody,
            OffsetDateTime createdAt) {}

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically claims {@code key}. Returns true if this caller inserted the
     * row (and so owns the key), false if any other request already had it.
     *
     * <p>{@code ON CONFLICT DO NOTHING} on the primary key turns "claim the
     * key" into a single atomic statement. If another request inserted the
     * same key but hasn't committed yet, Postgres makes this one wait for
     * that commit, so exactly one caller ever sees "1 row inserted".
     */
    public boolean tryInsertProcessing(String key, String requestHash, OffsetDateTime now) {
        return jdbc.update(
                        "INSERT INTO idempotency_records (idempotency_key, request_hash, status, created_at, updated_at)"
                                + " VALUES (?, ?, 'PROCESSING', ?, ?) ON CONFLICT (idempotency_key) DO NOTHING",
                        key, requestHash, now, now)
                == 1;
    }

    public Optional<IdempotencyRecord> find(String key) {
        return jdbc.query(
                        "SELECT idempotency_key, request_hash, status::text AS status, response_status_code,"
                                + " response_body::text AS response_body, created_at"
                                + " FROM idempotency_records WHERE idempotency_key = ?",
                        (rs, i) -> new IdempotencyRecord(
                                rs.getString("idempotency_key"),
                                rs.getString("request_hash"),
                                Status.valueOf(rs.getString("status")),
                                (Integer) rs.getObject("response_status_code"),
                                rs.getString("response_body"),
                                rs.getObject("created_at", OffsetDateTime.class)),
                        key)
                .stream()
                .findFirst();
    }

    /**
     * Takes over a key whose owner apparently died mid-request: still
     * PROCESSING and older than {@code staleBefore}. A single conditional
     * UPDATE is a compare-and-set, so if several waiters notice the stale key
     * at once, exactly one of them wins.
     */
    public boolean tryReclaimStale(String key, String requestHash, OffsetDateTime staleBefore, OffsetDateTime now) {
        return jdbc.update(
                        "UPDATE idempotency_records SET request_hash = ?, created_at = ?, updated_at = ?"
                                + " WHERE idempotency_key = ? AND status = 'PROCESSING' AND created_at < ?",
                        requestHash, now, now, key, staleBefore)
                == 1;
    }

    /**
     * Stores the response that every future retry with this key will
     * replay. Callers run this in the same DB transaction as the payment
     * itself, so the money movement and the stored response commit together
     * or not at all.
     */
    public void complete(String key, int statusCode, String responseBodyJson, OffsetDateTime now) {
        jdbc.update(
                "UPDATE idempotency_records SET status = 'COMPLETED', response_status_code = ?,"
                        + " response_body = ?::json, updated_at = ? WHERE idempotency_key = ?",
                statusCode, responseBodyJson, now, key);
    }
}
