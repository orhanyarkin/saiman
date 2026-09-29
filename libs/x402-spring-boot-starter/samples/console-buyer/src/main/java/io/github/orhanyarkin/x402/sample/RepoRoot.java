package io.github.orhanyarkin.x402.sample;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the saiman repository root from this sample's own working directory, rather than trusting
 * the invoking shell's current directory.
 *
 * <p>{@code bootRun}'s working directory is this sample's own project directory ({@code
 * libs/x402-spring-boot-starter/samples/console-buyer}), not wherever {@code make}/{@code gradlew}
 * happened to be invoked from. This walks up from there looking for a directory containing a
 * {@code Makefile}, a root {@code settings.gradle.kts}, {@code gradle/libs.versions.toml} and
 * {@code libs/x402-spring-boot-starter} — this sample has its own {@code settings.gradle.kts} but
 * none of the other three, so the combination uniquely identifies the repo root rather than
 * stopping one level too early (or matching some unrelated ancestor directory that happens to have
 * a {@code Makefile}).
 *
 * <p>Only called when actually needed: {@code new-wallet} always needs it (to write {@code
 * secrets/buyer.key}), but {@code buy}/{@code replay}/{@code testnet-check} only need it when
 * {@code X402_BUYER_PRIVATE_KEY} is unset and the key must come from that file instead — see
 * {@link ConsoleBuyerApplication}.
 */
final class RepoRoot {

    private RepoRoot() {}

    static Path locate() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (looksLikeRepoRoot(dir)) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate the saiman repository root (no ancestor directory of "
                + Path.of("").toAbsolutePath()
                + " has a Makefile, settings.gradle.kts, gradle/libs.versions.toml and"
                + " libs/x402-spring-boot-starter)");
    }

    private static boolean looksLikeRepoRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("Makefile"))
                && Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                && Files.isRegularFile(dir.resolve("gradle/libs.versions.toml"))
                && Files.isDirectory(dir.resolve("libs/x402-spring-boot-starter"));
    }
}
