package dev.truestate.payments.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 of a request body's canonical JSON: keys sorted, no whitespace.
 * Used to detect an idempotency key being reused with a different payload.
 *
 * <p>Produces exactly the same hash as the Python service's
 * {@code json.dumps(payload, sort_keys=True, separators=(",", ":"))}, so an
 * idempotency key is recognised consistently by both services.
 */
public final class RequestHasher {

    // Plain mapper on purpose: no naming strategy or modules, just compact output.
    private static final ObjectMapper CANONICAL = new ObjectMapper();

    private RequestHasher() {}

    /** Values must already be JSON-ready primitives (strings, numbers). */
    public static String hash(Map<String, ?> payload) {
        try {
            String canonical = CANONICAL.writeValueAsString(new TreeMap<>(payload));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("could not hash request", e);
        }
    }
}
