package io.github.orhanyarkin.x402.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link BuyCommand#printSettlement}'s output sanitization directly, without needing a
 * full HTTP round trip through {@link io.github.orhanyarkin.x402.client.X402PaymentInterceptor}
 * (which, upstream, already refuses to commit a settlement with a malformed {@code transaction} —
 * see that method's Javadoc for why this is still tested independently).
 */
class BuyCommandTest {

    private static final String PAYER = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C";

    @Test
    void wellFormedTransactionPrintsTheBaseScanLink() {
        String wellFormed = "0x" + "ab".repeat(32);
        String stdout = printedOutputFor(settlement(wellFormed));

        assertThat(stdout).contains("tx hash: " + wellFormed);
        assertThat(stdout).contains("https://sepolia.basescan.org/tx/" + wellFormed);
    }

    @Test
    void controlCharacterTransactionNeverReachesStdoutOrTheBaseScanLink() {
        // ESC + a CSI colour-change sequence: the classic terminal-escape-sequence attack,
        // embedded in an otherwise plausible-length value.
        String malicious = "\u001b[31m" + "a".repeat(60);
        String stdout = printedOutputFor(settlement(malicious));

        assertThat(stdout).doesNotContainPattern("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]");
        assertThat(stdout).doesNotContain("basescan.org");
    }

    @Test
    void nullTransactionNeverThrowsAndNeverPrintsTheLink() {
        String stdout = printedOutputFor(settlement(null));

        assertThat(stdout).contains("tx hash: ");
        assertThat(stdout).doesNotContain("basescan.org");
    }

    @Test
    void emptyTransactionNeverPrintsTheLink() {
        String stdout = printedOutputFor(settlement(""));

        assertThat(stdout).contains("tx hash: ");
        assertThat(stdout).doesNotContain("basescan.org");
    }

    private static SettlementResponse settlement(String transaction) {
        return new SettlementResponse(
                true, null, null, PAYER, transaction, TestnetAssets.NETWORK, "1000", null, null, null);
    }

    private static String printedOutputFor(SettlementResponse settlement) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        BuyCommand.printSettlement(settlement, new PrintStream(captured, true, StandardCharsets.UTF_8));
        return captured.toString(StandardCharsets.UTF_8);
    }
}
