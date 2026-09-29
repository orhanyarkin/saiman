package io.github.orhanyarkin.x402.sample;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the buyer's private key: {@code X402_BUYER_PRIVATE_KEY} first, then {@code
 * secrets/buyer.key} under the repository root (docs/design/m1-x402.md "Console buyer and make
 * targets"; ADR-0009).
 *
 * <p>Split into two steps deliberately: {@link #fromEnvironment()} needs no repository root at
 * all, so {@link ConsoleBuyerApplication} only calls {@link #fromFile} (which does) when the
 * environment variable is actually absent — resolving the repository root is otherwise unnecessary
 * work that would also fail this sample outside a saiman checkout for no reason.
 *
 * <p>Never logs or prints the resolved key; callers must not either.
 */
final class BuyerKeySource {

    private static final String ENV_VAR = "X402_BUYER_PRIVATE_KEY";
    private static final String KEY_FILE = "secrets/buyer.key";

    private BuyerKeySource() {}

    static Optional<String> fromEnvironment() {
        String fromEnv = System.getenv(ENV_VAR);
        return fromEnv != null && !fromEnv.isBlank() ? Optional.of(fromEnv.trim()) : Optional.empty();
    }

    static Optional<String> fromFile(Path repoRoot) {
        Path keyFile = repoRoot.resolve(KEY_FILE);
        if (!Files.isRegularFile(keyFile)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(keyFile).trim();
            return content.isEmpty() ? Optional.empty() : Optional.of(content);
        } catch (IOException e) {
            // Safe to chain: an IOException here is a filesystem-level failure (permissions, a
            // missing parent directory...) whose own message carries the file path, never the key
            // content that would only exist if the read had actually succeeded.
            throw new UncheckedIOException("failed to read the buyer key file at " + keyFile, e);
        }
    }
}
