package dev.truestate.payments.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfig {

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Timestamps in exactly the form the Python (Pydantic) service emits:
     * UTC with a "Z" suffix, and a 6-digit microsecond fraction that is
     * omitted entirely when it's zero, e.g. {@code 2026-11-11T19:32:02.932304Z}.
     */
    @Bean
    public Module pydanticCompatibleTimestamps() {
        SimpleModule module = new SimpleModule("pydantic-timestamps");
        module.addSerializer(OffsetDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(format(value));
            }
        });
        return module;
    }

    static String format(OffsetDateTime value) {
        OffsetDateTime utc = value.withOffsetSameInstant(ZoneOffset.UTC);
        int micros = utc.getNano() / 1_000;
        String fraction = micros == 0 ? "" : String.format(".%06d", micros);
        return SECONDS.format(utc) + fraction + "Z";
    }

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Idempotent Payment Processing API (Java)")
                        .version("0.1.0")
                        .description("Spring Boot port of TrueState. Exactly-once payments under retries:"
                                + " Idempotency-Key replay, an explicit transaction state machine, integer-cent"
                                + " amounts, and PostgreSQL row locks taken in a fixed order."));
    }
}
