package dev.truestate.payments.domain;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * "Now" in UTC, truncated to microseconds, which is the precision Postgres
 * {@code timestamptz} stores. Truncating up front means the value we return
 * in a response is exactly the value we wrote, so a later GET (or an
 * idempotent replay) shows identical timestamps.
 */
@Component
public class Timestamps {

    private final Clock clock;

    public Timestamps() {
        this(Clock.systemUTC());
    }

    public Timestamps(Clock clock) {
        this.clock = clock;
    }

    public OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
