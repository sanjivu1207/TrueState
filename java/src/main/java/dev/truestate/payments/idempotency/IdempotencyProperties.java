package dev.truestate.payments.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param staleProcessingAfter     a PROCESSING key older than this is assumed abandoned (its request
 *                                 crashed) and may be reclaimed by a retry
 * @param waitForCompletionTimeout how long a duplicate waits for the in-flight original before giving up with 409
 * @param pollInterval             how often a waiting duplicate re-checks the key
 */
@ConfigurationProperties("payments.idempotency")
public record IdempotencyProperties(
        Duration staleProcessingAfter, Duration waitForCompletionTimeout, Duration pollInterval) {}
