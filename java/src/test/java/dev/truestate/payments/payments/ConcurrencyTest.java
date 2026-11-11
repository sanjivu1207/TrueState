package dev.truestate.payments.payments;

import static dev.truestate.payments.support.Api.payment;
import static dev.truestate.payments.support.Api.uniqueKey;
import static org.assertj.core.api.Assertions.assertThat;

import dev.truestate.payments.support.Api.Res;
import dev.truestate.payments.support.Concurrently;
import dev.truestate.payments.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The tests that prove the core claim: exactly-once effects under
 * concurrent, at-least-once delivery. Ports of tests/test_concurrency.py,
 * plus two races the Python suite doesn't cover.
 *
 * <p>Every test checks the DATABASE, not just HTTP status codes: the
 * original lost-update bug returned 201 for requests whose debits never landed.
 */
class ConcurrencyTest extends PostgresIntegrationTest {

    @Test
    void fiftyConcurrentIdenticalRequestsCreateExactlyOneTransaction() throws Exception {
        String payer = api.createAccount("Payer", 10_000);
        String payee = api.createAccount("Payee", 0);
        String key = uniqueKey();
        Map<String, Object> body = payment(payer, payee, 1_000);

        List<Res> results = Concurrently.run(50, i -> api.post("/payments", body, key));

        assertThat(results).extracting(Res::status).containsOnly(201);
        Set<String> distinctBodies = results.stream().map(Res::body).collect(Collectors.toSet());
        assertThat(distinctBodies).as("all 50 responses must be byte-identical").hasSize(1);

        assertThat(transactionRowsForKey(key)).as("exactly one transaction row, not 50").isEqualTo(1);
        assertThat(api.balance(payer)).as("debited once").isEqualTo(9_000);
        assertThat(api.balance(payee)).as("credited once").isEqualTo(1_000);
    }

    @Test
    void concurrentOverdraftRaceNeverTakesBalanceNegative() throws Exception {
        String payer = api.createAccount("Payer", 10_000);
        String payee = api.createAccount("Payee", 0);

        // 20 distinct 1000-cent debits (different keys, so genuinely separate
        // payments) at once, against an account that can only cover 10.
        List<Res> results = Concurrently.run(20, i -> api.pay(payer, payee, 1_000));

        long successes = results.stream().filter(r -> r.status() == 201).count();
        long declined = results.stream().filter(r -> r.status() == 402).count();
        assertThat(successes + declined).as("only 201 or 402, no errors").isEqualTo(20);

        long payerBalance = api.balance(payer);
        long payeeBalance = api.balance(payee);
        assertThat(payerBalance).as("never negative").isGreaterThanOrEqualTo(0);
        assertThat(payerBalance + payeeBalance).as("money conserved").isEqualTo(10_000);
        assertThat(successes).as("10,000 / 1,000 = exactly 10 can succeed").isEqualTo(10);
        assertThat(payerBalance).isZero();

        // Every 201 corresponds to a debit that actually landed (no lost updates).
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM transactions WHERE status = 'AUTHORIZED'", Integer.class))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE status = 'FAILED'", Integer.class))
                .isEqualTo(10);
    }

    /**
     * Regression test for a race found in the Python service during the
     * port: refund checked the payment's status without locking its row, so
     * concurrent refunds could each see CAPTURED and each reverse the money.
     */
    @Test
    void concurrentRefundsOfOnePaymentRefundExactlyOnce() throws Exception {
        String payer = api.createAccount("Payer", 10_000);
        // Payee holds plenty, so the "payee can't cover the refund" check
        // can't mask a double refund.
        String payee = api.createAccount("Payee", 50_000);
        String paymentId = api.authorize(payer, payee, 1_000);
        assertThat(api.post("/payments/" + paymentId + "/capture").status()).isEqualTo(200);

        List<Res> results = Concurrently.run(20, i -> api.post("/payments/" + paymentId + "/refund"));

        assertThat(results.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.status() == 409).count()).isEqualTo(19);
        assertThat(api.balance(payer)).as("refunded once, not more").isEqualTo(10_000);
        assertThat(api.balance(payee)).isEqualTo(50_000);
        assertThat(api.get("/payments/" + paymentId).json().get("status").asText()).isEqualTo("refunded");
    }

    /**
     * Transfers A->B and B->A at the same time are the textbook deadlock
     * (each locks "its" sender first, then waits on the other). Fixed lock
     * order means Postgres never has to abort one: every request succeeds.
     */
    @Test
    void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        String a = api.createAccount("A", 100_000);
        String b = api.createAccount("B", 100_000);

        List<Res> results = Concurrently.run(60, i -> i % 2 == 0 ? api.pay(a, b, 100) : api.pay(b, a, 100));

        assertThat(results).extracting(Res::status).containsOnly(201);
        assertThat(api.balance(a)).isEqualTo(100_000);
        assertThat(api.balance(b)).isEqualTo(100_000);
    }
}
