package dev.truestate.payments.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Minimal real-HTTP client for the API under test (no MockMvc: requests go through Tomcat). */
public final class Api {

    public record Res(int status, String body) {
        public JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String base;

    Api(String base) {
        this.base = base;
    }

    public Res get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    public Res post(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.noBody()));
    }

    public Res post(String path, Object body) {
        return post(path, body, null);
    }

    public Res post(String path, Object body, String idempotencyKey) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body)));
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return send(b);
    }

    // ---- domain helpers, mirroring tests/helpers.py ----

    public String createAccount(String name, long balanceCents) {
        Res r = post("/accounts", Map.of("owner_name", name, "starting_balance_cents", balanceCents));
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().get("id").asText();
    }

    public long balance(String accountId) {
        Res r = get("/accounts/" + accountId + "/balance");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json().get("balance_cents").asLong();
    }

    public static Map<String, Object> payment(String from, String to, long amountCents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from_account_id", from);
        body.put("to_account_id", to);
        body.put("amount_cents", amountCents);
        return body;
    }

    public Res pay(String from, String to, long amountCents) {
        return post("/payments", payment(from, to, amountCents), uniqueKey());
    }

    /** Authorizes a payment and returns its id. */
    public String authorize(String from, String to, long amountCents) {
        Res r = pay(from, to, amountCents);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().get("id").asText();
    }

    public static String uniqueKey() {
        return UUID.randomUUID().toString();
    }

    private Res send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> r = http.send(request.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String write(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
