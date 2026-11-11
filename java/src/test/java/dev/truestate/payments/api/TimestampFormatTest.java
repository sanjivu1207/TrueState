package dev.truestate.payments.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Timestamps must render exactly like Pydantic so the two services' bodies are interchangeable. */
class TimestampFormatTest {

    @Test
    void microsecondsAreAlwaysSixDigits() {
        assertThat(JsonConfig.format(OffsetDateTime.of(2026, 11, 11, 19, 32, 2, 932_304_000, ZoneOffset.UTC)))
                .isEqualTo("2026-11-11T19:32:02.932304Z");
        assertThat(JsonConfig.format(OffsetDateTime.of(2026, 11, 11, 19, 32, 2, 932_000_000, ZoneOffset.UTC)))
                .as("not shortened to .932")
                .isEqualTo("2026-11-11T19:32:02.932000Z");
    }

    @Test
    void zeroFractionIsOmittedLikePydantic() {
        assertThat(JsonConfig.format(OffsetDateTime.of(2026, 11, 11, 19, 32, 2, 0, ZoneOffset.UTC)))
                .isEqualTo("2026-11-11T19:32:02Z");
    }

    @Test
    void nonUtcOffsetsAreConvertedToZ() {
        assertThat(JsonConfig.format(OffsetDateTime.of(2026, 11, 11, 13, 32, 2, 1_000, ZoneOffset.ofHours(-6))))
                .isEqualTo("2026-11-11T19:32:02.000001Z");
    }
}
