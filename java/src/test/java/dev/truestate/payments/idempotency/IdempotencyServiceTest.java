package dev.truestate.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.truestate.payments.domain.Timestamps;
import dev.truestate.payments.persistence.IdempotencyRepository;
import dev.truestate.payments.persistence.IdempotencyRepository.IdempotencyRecord;
import dev.truestate.payments.persistence.IdempotencyRepository.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the reserve/replay decision logic. The repository is mocked
 * here only to drive each branch; the same behaviour against real Postgres
 * (ON CONFLICT, concurrent claims) is covered by ConcurrencyTest and
 * IdempotencyIntegrationTest.
 */
class IdempotencyServiceTest {

    private static final String KEY = "key-1";
    private static final String HASH = "hash-a";
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneOffset.UTC);

    private IdempotencyRepository repo;
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        repo = mock(IdempotencyRepository.class);
        Timestamps fixedClock = new Timestamps(Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
        IdempotencyProperties props =
                new IdempotencyProperties(Duration.ofSeconds(30), Duration.ofMillis(200), Duration.ofMillis(10));
        service = new IdempotencyService(repo, props, fixedClock);
    }

    @Test
    void freshKeyIsOwned() {
        when(repo.tryInsertProcessing(KEY, HASH, NOW)).thenReturn(true);

        assertThat(service.reserve(KEY, HASH)).isInstanceOf(ReserveResult.Owned.class);
        verify(repo, never()).find(anyString());
    }

    @Test
    void completedKeyWithSameHashIsReplayedVerbatim() {
        when(repo.tryInsertProcessing(any(), any(), any())).thenReturn(false);
        when(repo.find(KEY)).thenReturn(Optional.of(completed(HASH, 201, "{\"id\":\"x\"}")));

        assertThat(service.reserve(KEY, HASH)).isEqualTo(new ReserveResult.Replay(201, "{\"id\":\"x\"}"));
    }

    @Test
    void storedErrorResponsesAreReplayedToo() {
        when(repo.tryInsertProcessing(any(), any(), any())).thenReturn(false);
        when(repo.find(KEY)).thenReturn(Optional.of(completed(HASH, 402, "{\"detail\":\"insufficient funds\"}")));

        assertThat(service.reserve(KEY, HASH)).isEqualTo(new ReserveResult.Replay(402, "{\"detail\":\"insufficient funds\"}"));
    }

    @Test
    void differentHashIsAMismatchWhetherCompletedOrProcessing() {
        when(repo.tryInsertProcessing(any(), any(), any())).thenReturn(false);

        when(repo.find(KEY)).thenReturn(Optional.of(completed("hash-b", 201, "{}")));
        assertThat(service.reserve(KEY, HASH)).isInstanceOf(ReserveResult.HashMismatch.class);

        when(repo.find(KEY)).thenReturn(Optional.of(processing("hash-b", NOW)));
        assertThat(service.reserve(KEY, HASH)).isInstanceOf(ReserveResult.HashMismatch.class);
    }

    @Test
    void inFlightDuplicateWaitsThenReplaysTheOriginalsResult() {
        when(repo.tryInsertProcessing(any(), any(), any())).thenReturn(false);
        when(repo.find(KEY)).thenReturn(
                Optional.of(processing(HASH, NOW)),
                Optional.of(processing(HASH, NOW)),
                Optional.of(completed(HASH, 201, "{\"done\":true}")));

        assertThat(service.reserve(KEY, HASH)).isEqualTo(new ReserveResult.Replay(201, "{\"done\":true}"));
        verify(repo, times(3)).find(KEY);
        verify(repo, never()).tryReclaimStale(any(), any(), any(), any());
    }

    @Test
    void inFlightDuplicateThatNeverFinishesTimesOut() {
        when(repo.tryInsertProcessing(any(), any(), any())).thenReturn(false);
        when(repo.find(KEY)).thenReturn(Optional.of(processing(HASH, NOW)));

        long start = System.nanoTime();
        assertThat(service.reserve(KEY, HASH)).isInstanceOf(ReserveResult.TimedOut.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    void staleProcessingKeyIsReclaimedWithA30SecondCutoff() {
        when(repo.tryInsertProcessing(any(), any(), any())).thenReturn(false);
        when(repo.find(KEY)).thenReturn(Optional.of(processing(HASH, NOW.minusSeconds(31))));
        when(repo.tryReclaimStale(KEY, HASH, NOW.minusSeconds(30), NOW)).thenReturn(true);

        assertThat(service.reserve(KEY, HASH)).isInstanceOf(ReserveResult.Owned.class);
    }

    @Test
    void processingKeyYoungerThan30SecondsIsNotReclaimed() {
        when(repo.tryInsertProcessing(any(), any(), any())).thenReturn(false);
        when(repo.find(KEY)).thenReturn(Optional.of(processing(HASH, NOW.minusSeconds(29))));

        assertThat(service.reserve(KEY, HASH)).isInstanceOf(ReserveResult.TimedOut.class);
        verify(repo, never()).tryReclaimStale(any(), any(), any(), any());
    }

    @Test
    void losingTheReclaimRaceMeansWaitingForTheWinner() {
        when(repo.tryInsertProcessing(any(), any(), any())).thenReturn(false);
        when(repo.find(KEY)).thenReturn(
                Optional.of(processing(HASH, NOW.minusSeconds(60))),
                Optional.of(completed(HASH, 201, "{\"winner\":true}")));
        when(repo.tryReclaimStale(any(), any(), any(), any())).thenReturn(false);

        assertThat(service.reserve(KEY, HASH)).isEqualTo(new ReserveResult.Replay(201, "{\"winner\":true}"));
    }

    @Test
    void recordVanishingBetweenInsertAndReadRetriesTheClaim() {
        when(repo.tryInsertProcessing(KEY, HASH, NOW)).thenReturn(false, true);
        when(repo.find(KEY)).thenReturn(Optional.empty());

        assertThat(service.reserve(KEY, HASH)).isInstanceOf(ReserveResult.Owned.class);
        verify(repo, times(2)).tryInsertProcessing(eq(KEY), eq(HASH), any());
    }

    private static IdempotencyRecord completed(String hash, int status, String body) {
        return new IdempotencyRecord(KEY, hash, Status.COMPLETED, status, body, NOW);
    }

    private static IdempotencyRecord processing(String hash, OffsetDateTime createdAt) {
        return new IdempotencyRecord(KEY, hash, Status.PROCESSING, null, null, createdAt);
    }
}
