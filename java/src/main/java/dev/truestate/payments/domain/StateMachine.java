package dev.truestate.payments.domain;

import static dev.truestate.payments.domain.TransactionStatus.AUTHORIZED;
import static dev.truestate.payments.domain.TransactionStatus.CAPTURED;
import static dev.truestate.payments.domain.TransactionStatus.FAILED;
import static dev.truestate.payments.domain.TransactionStatus.PENDING;
import static dev.truestate.payments.domain.TransactionStatus.REFUNDED;
import static dev.truestate.payments.domain.TransactionStatus.SETTLED;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for which status changes are legal. Every code
 * path that changes a transaction's status goes through {@link #transition},
 * so an illegal jump is rejected everywhere, not just in whichever endpoint
 * remembered to check.
 *
 * <pre>
 *   PENDING -----> AUTHORIZED -----> CAPTURED -----> SETTLED
 *      |               |                |               |
 *      v               v                +------>--------+
 *    FAILED          FAILED                     v
 *                                            REFUNDED
 * </pre>
 *
 * REFUNDED is only reachable from CAPTURED or SETTLED: a payment that never
 * moved money can't be refunded. FAILED and REFUNDED are terminal.
 */
public final class StateMachine {

    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED =
            new EnumMap<>(TransactionStatus.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(AUTHORIZED, FAILED));
        ALLOWED.put(AUTHORIZED, EnumSet.of(CAPTURED, FAILED));
        ALLOWED.put(CAPTURED, EnumSet.of(SETTLED, REFUNDED));
        ALLOWED.put(SETTLED, EnumSet.of(REFUNDED));
        ALLOWED.put(FAILED, EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(REFUNDED, EnumSet.noneOf(TransactionStatus.class));
    }

    private StateMachine() {}

    public static boolean isAllowed(TransactionStatus from, TransactionStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /**
     * Returns {@code to} if {@code from -> to} is legal, otherwise throws.
     * Pure function: callers persist the result inside their own DB transaction.
     */
    public static TransactionStatus transition(TransactionStatus from, TransactionStatus to) {
        if (!isAllowed(from, to)) {
            throw new InvalidTransitionException(from, to);
        }
        return to;
    }

    /** Throws unless {@code from -> to} is legal, without performing it. */
    public static void requireAllowed(TransactionStatus from, TransactionStatus to) {
        transition(from, to);
    }
}
