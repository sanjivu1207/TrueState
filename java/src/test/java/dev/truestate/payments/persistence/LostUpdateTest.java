package dev.truestate.payments.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.truestate.payments.domain.Account;
import dev.truestate.payments.domain.Timestamps;
import dev.truestate.payments.support.Concurrently;
import dev.truestate.payments.support.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Reproduces the lost-update race that the Python project hit (19 of 20
 * requests reported success but only 2 debits landed, because SQLite ignored
 * SELECT ... FOR UPDATE), then shows that the row lock fixes it.
 *
 * <p>Both variants run the SAME read-check-write debit through the
 * production {@link AccountRepository}; the only difference is the read:
 * {@code findById} (plain SELECT) vs {@code lockInOrder} (SELECT ... FOR UPDATE).
 *
 * <p><b>Why this is deterministic, not timing-dependent:</b> after reading
 * the balance, each debit waits until every other debit is either (a) also
 * holding a read ("idle in transaction"), (b) blocked on a row lock, or
 * (c) finished, as observed in {@code pg_stat_activity}. So the race window
 * is always fully open: unlocked, all 20 reads happen before any write;
 * locked, the other 19 are provably queued on the lock. Nothing depends on
 * thread scheduling luck.
 */
class LostUpdateTest extends PostgresIntegrationTest {

    private static final String APP_NAME = "lost-update-harness";
    private static final int DEBITS = 20;
    private static final long START_BALANCE = 10_000;
    private static final long AMOUNT = 1_000;

    @Autowired
    AccountRepository accounts;

    @Autowired
    Timestamps timestamps;

    enum Read {
        PLAIN_SELECT,
        SELECT_FOR_UPDATE
    }

    record Outcome(int reportedSuccesses, long finalBalance) {
        long debitsThatLanded() {
            return (START_BALANCE - finalBalance) / AMOUNT;
        }
    }

    @Test
    void withoutRowLockConcurrentDebitsReportSuccessButLoseUpdates() throws Exception {
        Outcome o = race(Read.PLAIN_SELECT);

        // Every request saw balance=10000 and "succeeded"...
        assertThat(o.reportedSuccesses()).isEqualTo(20);
        // ...but each wrote 10000 - 1000, so only ONE debit actually landed
        // and 19 were silently lost. (Also an overdraft: 20 debits "approved"
        // against funds for 10.)
        assertThat(o.finalBalance()).isEqualTo(9_000);
        assertThat(o.debitsThatLanded()).isEqualTo(1);
    }

    @Test
    void withSelectForUpdateEveryReportedSuccessLands() throws Exception {
        Outcome o = race(Read.SELECT_FOR_UPDATE);

        assertThat(o.reportedSuccesses()).as("only the 10 the balance covers").isEqualTo(10);
        assertThat(o.debitsThatLanded()).as("every success really landed").isEqualTo(o.reportedSuccesses());
        assertThat(o.finalBalance()).isZero();
    }

    private Outcome race(Read mode) throws Exception {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = timestamps.now();
        accounts.insert(new Account(accountId, "Payer", START_BALANCE, now, now));

        AtomicInteger finished = new AtomicInteger();
        List<Boolean> results = Concurrently.run(DEBITS, i -> {
            try (Connection conn = harnessConnection()) {
                conn.setAutoCommit(false);
                AccountRepository repo = new AccountRepository(new JdbcTemplate(new SingleConnectionDataSource(conn, true)));

                Account read = mode == Read.PLAIN_SELECT
                        ? repo.findById(accountId).orElseThrow()
                        : repo.lockInOrder(accountId, accountId).get(accountId);

                awaitEveryDebitInFlight(finished);

                boolean approved = read.balanceCents() >= AMOUNT;
                if (approved) {
                    repo.updateBalance(accountId, read.balanceCents() - AMOUNT, timestamps.now());
                }
                conn.commit();
                return approved;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                finished.incrementAndGet();
            }
        });

        int successes = (int) results.stream().filter(Boolean::booleanValue).count();
        return new Outcome(successes, accounts.findById(accountId).orElseThrow().balanceCents());
    }

    /** Blocks until all debits are holding a read, waiting on a row lock, or done. */
    private void awaitEveryDebitInFlight(AtomicInteger finished) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (true) {
            Integer inFlight = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE application_name = ?"
                            + " AND (state = 'idle in transaction' OR wait_event_type = 'Lock')",
                    Integer.class,
                    APP_NAME);
            if (inFlight + finished.get() >= DEBITS) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("harness timed out: " + inFlight + " in flight, " + finished + " done");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** A dedicated, tagged connection per debit (not from the app's pool) so pg_stat_activity can find it. */
    private static Connection harnessConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES.getUsername());
        props.setProperty("password", POSTGRES.getPassword());
        props.setProperty("ApplicationName", APP_NAME);
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), props);
    }
}
