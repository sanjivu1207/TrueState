package dev.truestate.payments.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.truestate.payments.support.Api.Res;
import dev.truestate.payments.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Capture/refund and illegal transitions over HTTP. Port of tests/test_state_machine.py. */
class StateMachineApiTest extends PostgresIntegrationTest {

    @Test
    void authorizeCaptureRefundHappyPath() {
        String a = api.createAccount("Alice", 10_000);
        String b = api.createAccount("Bob", 0);
        String txn = api.authorize(a, b, 3_000);
        assertThat(api.get("/payments/" + txn).json().get("status").asText()).isEqualTo("authorized");

        Res captured = api.post("/payments/" + txn + "/capture");
        assertThat(captured.status()).as(captured.body()).isEqualTo(200);
        assertThat(captured.json().get("status").asText()).isEqualTo("captured");
        // capture is a pure status change: funds already moved at authorization
        assertThat(api.balance(a)).isEqualTo(7_000);
        assertThat(api.balance(b)).isEqualTo(3_000);

        Res refunded = api.post("/payments/" + txn + "/refund");
        assertThat(refunded.status()).as(refunded.body()).isEqualTo(200);
        assertThat(refunded.json().get("status").asText()).isEqualTo("refunded");
        assertThat(api.balance(a)).isEqualTo(10_000);
        assertThat(api.balance(b)).isZero();
    }

    @Test
    void captureAfterRefundIsRejected() {
        String txn = capturedAndRefunded();

        Res r = api.post("/payments/" + txn + "/capture");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("detail").asText()).isEqualTo("cannot capture a payment in status 'refunded'");
    }

    @Test
    void doubleRefundIsRejected() {
        String txn = capturedAndRefunded();

        Res r = api.post("/payments/" + txn + "/refund");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("detail").asText()).isEqualTo("cannot refund a payment in status 'refunded'");
    }

    @Test
    void refundWithoutCaptureIsRejected() {
        String a = api.createAccount("Alice", 10_000);
        String b = api.createAccount("Bob", 0);
        String txn = api.authorize(a, b, 1_000);

        Res r = api.post("/payments/" + txn + "/refund");

        assertThat(r.status()).isEqualTo(409);
        assertThat(api.balance(b)).as("rejected refund must not move money").isEqualTo(1_000);
    }

    @Test
    void refundBlockedWhenReceiverNoLongerHasTheFunds() {
        String a = api.createAccount("Carol", 10_000);
        String b = api.createAccount("Dave", 0);
        String txn = api.authorize(a, b, 4_000);
        api.post("/payments/" + txn + "/capture");

        // Dave spends the funds onward before the refund is attempted
        api.authorize(b, a, 4_000);
        assertThat(api.balance(b)).isZero();

        Res r = api.post("/payments/" + txn + "/refund");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("detail").asText())
                .isEqualTo("cannot refund: receiving account no longer holds enough balance to reverse");

        // must not have silently flipped to refunded despite the rejection
        assertThat(api.get("/payments/" + txn).json().get("status").asText()).isEqualTo("captured");
        assertThat(api.balance(a)).isEqualTo(10_000);
    }

    @Test
    void captureUnknownPaymentReturns404() {
        assertThat(api.post("/payments/" + UUID.randomUUID() + "/capture").status()).isEqualTo(404);
    }

    @Test
    void refundUnknownPaymentReturns404() {
        assertThat(api.post("/payments/" + UUID.randomUUID() + "/refund").status()).isEqualTo(404);
    }

    @Test
    void failedPaymentCannotBeCaptured() {
        String a = api.createAccount("Alice", 10);
        String b = api.createAccount("Bob", 0);
        String failed = api.pay(a, b, 1_000).json().get("id").asText();

        Res r = api.post("/payments/" + failed + "/capture");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("detail").asText()).isEqualTo("cannot capture a payment in status 'failed'");
    }

    private String capturedAndRefunded() {
        String a = api.createAccount("Alice", 10_000);
        String b = api.createAccount("Bob", 0);
        String txn = api.authorize(a, b, 1_000);
        assertThat(api.post("/payments/" + txn + "/capture").status()).isEqualTo(200);
        assertThat(api.post("/payments/" + txn + "/refund").status()).isEqualTo(200);
        return txn;
    }
}
