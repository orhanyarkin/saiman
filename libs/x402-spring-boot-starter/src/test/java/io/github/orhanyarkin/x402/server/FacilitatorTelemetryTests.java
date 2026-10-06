package io.github.orhanyarkin.x402.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.facilitator.FacilitatorReason;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FacilitatorTelemetryTests {

    private static final String FROM = "0xAbCdEf0123456789aBcDeF0123456789AbCdEf01";
    private static final String NONCE = "0x" + "ab".repeat(32);

    @Test
    void nonceRefIsEightLowerHexCharsAndStable() {
        String ref = FacilitatorTelemetry.nonceRef(FROM, NONCE);

        assertThat(ref).matches("[0-9a-f]{8}");
        assertThat(FacilitatorTelemetry.nonceRef(FROM, NONCE)).isEqualTo(ref);
        assertThat(FacilitatorTelemetry.nonceRef(FROM.toLowerCase(Locale.ROOT), "0x" + "AB".repeat(32)))
                .as("case-insensitive over from and nonce")
                .isEqualTo(ref);
    }

    @Test
    void nonceRefDiffersPerNonceAndPayerAndRevealsNothingOfTheNonce() {
        Set<String> refs = new HashSet<>();
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String nonce = "0x" + HexFormat.of().formatHex(bytes);
            String ref = FacilitatorTelemetry.nonceRef(FROM, nonce);
            refs.add(ref);
            assertThat(nonce)
                    .as("a 32-bit digest prefix is not a slice of the nonce")
                    .doesNotContain(ref);
        }
        assertThat(refs.size()).as("32-bit prefix, 200 samples").isGreaterThan(195);
        assertThat(FacilitatorTelemetry.nonceRef("0x" + "11".repeat(20), NONCE))
                .isNotEqualTo(FacilitatorTelemetry.nonceRef(FROM, NONCE));
    }

    @Test
    void knownCodesMapToTheirConstantAndAbsentToNone() {
        assertThat(FacilitatorReason.fromCode("invalid_exact_evm_transaction_failed"))
                .isEqualTo(FacilitatorReason.INVALID_EXACT_EVM_TRANSACTION_FAILED);
        assertThat(FacilitatorReason.fromCode("settlement_pending")).isEqualTo(FacilitatorReason.SETTLEMENT_PENDING);
        assertThat(FacilitatorReason.fromCode("insufficient_funds")).isEqualTo(FacilitatorReason.INSUFFICIENT_FUNDS);
        assertThat(FacilitatorReason.fromCode(null)).isEqualTo(FacilitatorReason.NONE);
        assertThat(FacilitatorReason.fromCode("")).isEqualTo(FacilitatorReason.NONE);
    }

    @Test
    void unknownCodesAreOtherSoCardinalityIsBounded() {
        Random random = new Random(11);
        Set<String> tags = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            tags.add(FacilitatorReason.fromCode("code_" + random.nextLong()).code());
        }
        tags.add(FacilitatorReason.fromCode("INVALID_EXACT_EVM_TRANSACTION_FAILED")
                .code());
        tags.add(FacilitatorReason.fromCode("unrecognised").code());
        assertThat(tags).containsExactly("other");
        assertThat(FacilitatorReason.values()).hasSizeLessThan(32);
    }
}
