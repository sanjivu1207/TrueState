package dev.truestate.payments.persistence;

import dev.truestate.payments.domain.Transaction;
import dev.truestate.payments.domain.TransactionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    private static final String SELECT = "SELECT id, idempotency_key, from_account_id, to_account_id, amount_cents,"
            + " status::text AS status, created_at, updated_at FROM transactions WHERE id = ?";

    private final JdbcTemplate jdbc;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Transaction t) {
        jdbc.update(
                "INSERT INTO transactions (id, idempotency_key, from_account_id, to_account_id, amount_cents,"
                        + " status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?::transaction_status, ?, ?)",
                t.id(),
                t.idempotencyKey(),
                t.fromAccountId(),
                t.toAccountId(),
                t.amountCents(),
                t.status().name(),
                t.createdAt(),
                t.updatedAt());
    }

    /** Plain read, no lock. For GET. */
    public Optional<Transaction> findById(UUID id) {
        return jdbc.query(SELECT, TransactionRepository::map, id).stream().findFirst();
    }

    /**
     * Read-and-lock ({@code FOR UPDATE}). Capture and refund check the
     * current status and then change it; without this lock two concurrent
     * refunds could both read CAPTURED and both reverse the money.
     */
    public Optional<Transaction> lockById(UUID id) {
        return jdbc.query(SELECT + " FOR UPDATE", TransactionRepository::map, id).stream().findFirst();
    }

    public void updateStatus(UUID id, TransactionStatus status, OffsetDateTime now) {
        jdbc.update(
                "UPDATE transactions SET status = ?::transaction_status, updated_at = ? WHERE id = ?",
                status.name(), now, id);
    }

    private static Transaction map(ResultSet rs, int row) throws SQLException {
        return new Transaction(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getObject("from_account_id", UUID.class),
                rs.getObject("to_account_id", UUID.class),
                rs.getLong("amount_cents"),
                TransactionStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
