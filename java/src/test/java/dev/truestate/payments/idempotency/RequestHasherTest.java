package dev.truestate.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.truestate.payments.payments.PaymentCommand;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

    /**
     * Expected hashes were produced by the Python service's own
     * app.idempotency.hash_request(jsonable_encoder(PaymentCreate(...))), so
     * this proves byte-for-byte compatibility across the two services.
     */
    @Test
    void matchesPythonServiceHashes() {
        assertThat(hash("83a47098-a6ad-40dd-99f4-55e2b235abc9", "25cddd43-92b8-44da-98c6-7ed77ad77939", 2500))
                .isEqualTo("1c35ade7f006340bb604c9a738a423ae32ba0d97d97df0d153ca67c7b98d1ed8");
        assertThat(hash("00000000-0000-0000-0000-000000000001", "ffffffff-ffff-ffff-ffff-ffffffffffff", 1))
                .isEqualTo("2395e4fffd03fc26dcf571707555864f68a588af2201063594a7e9c6e204d3d1");
    }

    @Test
    void independentOfKeyOrder() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", 1);
        ab.put("b", "x");
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", "x");
        ba.put("a", 1);

        assertThat(RequestHasher.hash(ab)).isEqualTo(RequestHasher.hash(ba));
    }

    @Test
    void anyFieldChangeChangesTheHash() {
        String base = hash("83a47098-a6ad-40dd-99f4-55e2b235abc9", "25cddd43-92b8-44da-98c6-7ed77ad77939", 2500);

        assertThat(hash("83a47098-a6ad-40dd-99f4-55e2b235abc9", "25cddd43-92b8-44da-98c6-7ed77ad77939", 2501))
                .isNotEqualTo(base);
        assertThat(hash("25cddd43-92b8-44da-98c6-7ed77ad77939", "83a47098-a6ad-40dd-99f4-55e2b235abc9", 2500))
                .as("swapping payer and payee is a different payment")
                .isNotEqualTo(base);
    }

    @Test
    void uppercaseUuidInputHashesTheSameAsLowercase() {
        // Clients may send uppercase UUIDs; parsed to UUID, they're the same payment.
        assertThat(hash("83A47098-A6AD-40DD-99F4-55E2B235ABC9", "25CDDD43-92B8-44DA-98C6-7ED77AD77939", 2500))
                .isEqualTo("1c35ade7f006340bb604c9a738a423ae32ba0d97d97df0d153ca67c7b98d1ed8");
    }

    private static String hash(String from, String to, long amount) {
        return RequestHasher.hash(new PaymentCommand(UUID.fromString(from), UUID.fromString(to), amount).hashPayload());
    }
}
