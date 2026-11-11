package dev.truestate.payments.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.truestate.payments.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SchemaAndLockOrderTest extends PostgresIntegrationTest {

    /**
     * Lock order must be the order Postgres itself sorts uuids in (which is
     * also what Python's sorted() on UUIDs gives). Java's UUID.compareTo
     * disagrees, which this test also demonstrates.
     */
    @Test
    void lockOrderComparatorMatchesPostgresUuidOrdering() {
        Random random = new Random(42);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(new UUID(random.nextLong(), random.nextLong()));
        }
        String values = ids.stream().map(id -> "('" + id + "'::uuid)").collect(Collectors.joining(","));

        List<UUID> postgresOrder = jdbc.queryForList(
                "SELECT id FROM (VALUES " + values + ") AS t(id) ORDER BY id", UUID.class);

        assertThat(ids.stream().sorted(AccountRepository.POSTGRES_UUID_ORDER).toList()).isEqualTo(postgresOrder);
        assertThat(ids.stream().sorted(Comparator.naturalOrder()).toList())
                .as("UUID.compareTo is signed, so it would NOT be safe for lock ordering")
                .isNotEqualTo(postgresOrder);
    }

    @Test
    void flywayAppliedTheBaselineMigration() {
        assertThat(jdbc.queryForObject(
                        "SELECT string_agg(version || ':' || success, ',') FROM flyway_schema_history", String.class))
                .isEqualTo("1:true");
        assertThat(jdbc.queryForList(
                        "SELECT enumlabel FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid"
                                + " WHERE t.typname = 'transaction_status' ORDER BY enumsortorder",
                        String.class))
                .containsExactly("PENDING", "AUTHORIZED", "CAPTURED", "SETTLED", "FAILED", "REFUNDED");
    }
}
