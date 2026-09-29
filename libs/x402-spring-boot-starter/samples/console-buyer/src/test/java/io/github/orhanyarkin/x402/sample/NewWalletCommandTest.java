package io.github.orhanyarkin.x402.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewWalletCommandTest {

    // 64 consecutive hex digits, with or without a leading 0x: the shape of a raw private key.
    private static final Pattern HEX_64 = Pattern.compile("(0x)?[0-9a-fA-F]{64}");

    @Test
    void writesAnOwnerOnlyKeyFileAndPrintsOnlyTheAddress(@TempDir Path repoRoot) throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        NewWalletCommand.run(repoRoot, new PrintStream(captured, true, StandardCharsets.UTF_8));

        Path keyFile = repoRoot.resolve("secrets/buyer.key");
        assertThat(keyFile).isRegularFile();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile)))
                .isEqualTo("rw-------");

        String stdout = captured.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("address:").contains("faucet.circle.com");
        // Stdout must never contain a 64-hex-digit private key, in the address line or anywhere else.
        assertThat(HEX_64.matcher(stdout).find()).isFalse();
    }

    @Test
    void theWrittenKeyFileParsesAsAValidPrivateKey(@TempDir Path repoRoot) throws Exception {
        NewWalletCommand.run(repoRoot, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        String key = Files.readString(repoRoot.resolve("secrets/buyer.key"));
        assertThat(key).matches("0x[0-9a-fA-F]{64}");
        // Round-trips through the starter's own signer: proves the generated key is well-formed,
        // not just well-shaped.
        assertThat(new io.github.orhanyarkin.x402.evm.PrivateKeyPaymentSigner(key).address())
                .matches("0x[0-9a-fA-F]{40}");
    }

    @Test
    void refusesToOverwriteAnExistingKey(@TempDir Path repoRoot) throws Exception {
        NewWalletCommand.run(repoRoot, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        String firstKey = Files.readString(repoRoot.resolve("secrets/buyer.key"));

        assertThatIllegalStateException()
                .isThrownBy(() -> NewWalletCommand.run(
                        repoRoot, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)))
                .withMessageContaining("refusing to overwrite");

        // The original key must be untouched.
        assertThat(Files.readString(repoRoot.resolve("secrets/buyer.key"))).isEqualTo(firstKey);
    }
}
