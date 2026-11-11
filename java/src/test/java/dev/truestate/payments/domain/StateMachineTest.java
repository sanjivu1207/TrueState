package dev.truestate.payments.domain;

import static dev.truestate.payments.domain.TransactionStatus.AUTHORIZED;
import static dev.truestate.payments.domain.TransactionStatus.CAPTURED;
import static dev.truestate.payments.domain.TransactionStatus.FAILED;
import static dev.truestate.payments.domain.TransactionStatus.PENDING;
import static dev.truestate.payments.domain.TransactionStatus.REFUNDED;
import static dev.truestate.payments.domain.TransactionStatus.SETTLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StateMachineTest {

    /** The spec, written out independently of the implementation (and of app/state_machine.py). */
    private static final Map<TransactionStatus, Set<TransactionStatus>> SPEC = Map.of(
            PENDING, EnumSet.of(AUTHORIZED, FAILED),
            AUTHORIZED, EnumSet.of(CAPTURED, FAILED),
            CAPTURED, EnumSet.of(SETTLED, REFUNDED),
            SETTLED, EnumSet.of(REFUNDED),
            FAILED, EnumSet.noneOf(TransactionStatus.class),
            REFUNDED, EnumSet.noneOf(TransactionStatus.class));

    /** All 36 (from, to) pairs, including self-transitions. */
    static Stream<Arguments> everyPair() {
        List<Arguments> pairs = new ArrayList<>();
        for (TransactionStatus from : TransactionStatus.values()) {
            for (TransactionStatus to : TransactionStatus.values()) {
                pairs.add(Arguments.of(from, to, SPEC.get(from).contains(to)));
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @MethodSource("everyPair")
    void everyTransitionMatchesTheSpec(TransactionStatus from, TransactionStatus to, boolean allowed) {
        assertThat(StateMachine.isAllowed(from, to)).isEqualTo(allowed);
        if (allowed) {
            assertThat(StateMachine.transition(from, to)).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> StateMachine.transition(from, to))
                    .isInstanceOf(InvalidTransitionException.class)
                    .hasMessage("cannot transition from '%s' to '%s'", from.value(), to.value());
        }
    }

    @Test
    void terminalStatesHaveNoWayOut() {
        for (TransactionStatus to : TransactionStatus.values()) {
            assertThat(StateMachine.isAllowed(FAILED, to)).isFalse();
            assertThat(StateMachine.isAllowed(REFUNDED, to)).isFalse();
        }
    }

    @Test
    void onlyMoneyMovedStatesCanBeRefunded() {
        assertThat(EnumSet.allOf(TransactionStatus.class).stream().filter(s -> StateMachine.isAllowed(s, REFUNDED)))
                .containsExactlyInAnyOrder(CAPTURED, SETTLED);
    }

    @Test
    void transactionTransitionUpdatesStatusAndTimestampOnly() {
        OffsetDateTime created = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime later = created.plusMinutes(5);
        Transaction t = new Transaction(UUID.randomUUID(), "k", UUID.randomUUID(), UUID.randomUUID(), 100, PENDING, created, created);

        Transaction authorized = t.transitionTo(AUTHORIZED, later);

        assertThat(authorized.status()).isEqualTo(AUTHORIZED);
        assertThat(authorized.updatedAt()).isEqualTo(later);
        assertThat(authorized.createdAt()).isEqualTo(created);
        assertThat(authorized).usingRecursiveComparison().ignoringFields("status", "updatedAt").isEqualTo(t);
        assertThat(t.status()).as("original is immutable").isEqualTo(PENDING);
    }

    @Test
    void illegalTransitionOnTransactionThrows() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Transaction t = new Transaction(UUID.randomUUID(), "k", UUID.randomUUID(), UUID.randomUUID(), 100, PENDING, now, now);

        assertThatThrownBy(() -> t.transitionTo(SETTLED, now))
                .isInstanceOfSatisfying(InvalidTransitionException.class, e -> {
                    assertThat(e.from()).isEqualTo(PENDING);
                    assertThat(e.to()).isEqualTo(SETTLED);
                });
    }

    @Test
    void apiValueIsLowercaseAndDatabaseNameIsUppercase() {
        assertThat(AUTHORIZED.value()).isEqualTo("authorized");
        assertThat(AUTHORIZED.name()).isEqualTo("AUTHORIZED");
    }
}
