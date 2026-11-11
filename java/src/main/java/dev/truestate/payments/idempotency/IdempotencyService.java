package dev.truestate.payments.idempotency;

import dev.truestate.payments.domain.Timestamps;
import dev.truestate.payments.persistence.IdempotencyRepository;
import dev.truestate.payments.persistence.IdempotencyRepository.IdempotencyRecord;
import dev.truestate.payments.persistence.IdempotencyRepository.Status;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The idempotency-key contract for POST /payments. A client retrying the same
 * logical request sends the same {@code Idempotency-Key}; the server must:
 * <ul>
 *   <li>process a brand-new key normally,
 *   <li>replay the stored response if that key already completed,
 *   <li>reject the key if it is reused with a different request body,
 *   <li>never let two concurrent requests for the same new key both process.
 * </ul>
 *
 * <p>{@link #reserve} runs OUTSIDE the payment's DB transaction: each
 * statement auto-commits, so a claimed key is visible to every other request
 * immediately, before any money moves.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final IdempotencyProperties properties;
    private final Timestamps timestamps;

    public IdempotencyService(IdempotencyRepository repository, IdempotencyProperties properties, Timestamps timestamps) {
        this.repository = repository;
        this.properties = properties;
        this.timestamps = timestamps;
    }

    public ReserveResult reserve(String key, String requestHash) {
        long deadline = System.nanoTime() + properties.waitForCompletionTimeout().toNanos();

        while (true) {
            if (repository.tryInsertProcessing(key, requestHash, timestamps.now())) {
                return new ReserveResult.Owned();
            }

            // An empty result means the row vanished between our insert and
            // this read; loop round and try to claim it again.
            Optional<IdempotencyRecord> found = repository.find(key);
            if (found.isPresent()) {
                IdempotencyRecord rec = found.get();
                if (!rec.requestHash().equals(requestHash)) {
                    return new ReserveResult.HashMismatch();
                }
                if (rec.status() == Status.COMPLETED) {
                    return new ReserveResult.Replay(rec.responseStatusCode(), rec.responseBody());
                }

                // Still PROCESSING: almost always a genuine concurrent duplicate.
                // Wait for it to finish and replay its result rather than racing
                // it. If it has been stuck long enough to be presumed dead, take over.
                OffsetDateTime now = timestamps.now();
                OffsetDateTime staleBefore = now.minus(properties.staleProcessingAfter());
                if (rec.createdAt().isBefore(staleBefore)
                        && repository.tryReclaimStale(key, requestHash, staleBefore, now)) {
                    return new ReserveResult.Owned();
                }
            }

            if (System.nanoTime() >= deadline) {
                return new ReserveResult.TimedOut();
            }
            sleep();
        }
    }

    private void sleep() {
        try {
            Thread.sleep(properties.pollInterval());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for idempotency key", e);
        }
    }
}
