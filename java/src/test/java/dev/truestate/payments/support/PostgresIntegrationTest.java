package dev.truestate.payments.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests: the full app on a random port, talking
 * to a REAL PostgreSQL 16 in a container. Never an in-memory database or a
 * mock: the guarantees under test (row locks, ON CONFLICT, isolation) are
 * Postgres behaviours, and the Python project found that SQLite silently
 * ignores SELECT ... FOR UPDATE, which hid a real lost-update race.
 *
 * <p>One container is started for the whole test run (singleton pattern) and
 * every test starts from empty tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected Api api;

    @BeforeEach
    void cleanDatabaseAndConnect() {
        jdbc.execute("TRUNCATE accounts, transactions, idempotency_records RESTART IDENTITY CASCADE");
        api = new Api("http://localhost:" + port);
    }

    protected int transactionRowsForKey(String idempotencyKey) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM transactions WHERE idempotency_key = ?", Integer.class, idempotencyKey);
    }
}
