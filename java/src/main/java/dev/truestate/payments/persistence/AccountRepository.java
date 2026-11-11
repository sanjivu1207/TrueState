package dev.truestate.payments.persistence;

import dev.truestate.payments.domain.Account;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    /**
     * The order Postgres sorts {@code uuid} values in: an unsigned byte-by-byte
     * comparison. {@link UUID#compareTo} compares the two halves as SIGNED
     * longs and gives a different order for about half of all pairs, so we
     * must not use it for lock ordering. Using Postgres' order also means the
     * Java and Python services lock in the same order if they share a database.
     */
    public static final Comparator<UUID> POSTGRES_UUID_ORDER = Comparator
            .comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    private static final String COLUMNS = "id, owner_name, balance_cents, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Account a) {
        jdbc.update(
                "INSERT INTO accounts (id, owner_name, balance_cents, version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 0, ?, ?)",
                a.id(), a.ownerName(), a.balanceCents(), a.createdAt(), a.updatedAt());
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM accounts WHERE id = ?", AccountRepository::map, id)
                .stream()
                .findFirst();
    }

    /**
     * Locks the given account rows ({@code SELECT ... FOR UPDATE}) one at a
     * time in a fixed global order, and returns those that exist.
     *
     * <p>Fixed ordering is what prevents deadlocks: if transfer A->B and
     * transfer B->A each grabbed "their" sender first, each could end up
     * holding one lock while waiting forever for the other's. When everyone
     * locks the lower id first, whoever gets the first lock is guaranteed to
     * be able to get the second, and everyone else queues behind it.
     *
     * <p>Must be called inside a transaction; the locks are held until it
     * commits or rolls back.
     */
    public Map<UUID, Account> lockInOrder(UUID first, UUID second) {
        List<UUID> ordered = List.of(first, second).stream()
                .distinct()
                .sorted(POSTGRES_UUID_ORDER)
                .toList();
        Map<UUID, Account> locked = new LinkedHashMap<>();
        for (UUID id : ordered) {
            jdbc.query("SELECT " + COLUMNS + " FROM accounts WHERE id = ? FOR UPDATE", AccountRepository::map, id)
                    .forEach(a -> locked.put(a.id(), a));
        }
        return locked;
    }

    public void updateBalance(UUID id, long balanceCents, OffsetDateTime now) {
        jdbc.update("UPDATE accounts SET balance_cents = ?, updated_at = ? WHERE id = ?", balanceCents, now, id);
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("owner_name"),
                rs.getLong("balance_cents"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
