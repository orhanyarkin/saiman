package io.github.orhanyarkin.x402.sample;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Writes files that other local users should not be able to read, with mode {@code 0600}. */
final class SecretFiles {

    private static final Set<PosixFilePermission> OWNER_READ_WRITE_ONLY = PosixFilePermissions.fromString("rw-------");

    private SecretFiles() {}

    /**
     * Writes {@code content} to {@code target} with mode {@code 0600}, replacing any existing
     * file. A sibling temporary file is created with that mode from the moment it exists (no
     * window where partial or full content is world/group readable), then atomically moved into
     * place — the captured payment signature this backs ({@code build/last-payment.txt}) is a
     * bearer instrument for its one authorization until {@code validBefore}, same as the buyer's
     * private key file, even though it lives under {@code build/} rather than {@code secrets/}.
     */
    static void writeOwnerOnly(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(
                parent,
                target.getFileName().toString(),
                ".tmp",
                PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE_ONLY));
        try {
            Files.writeString(temp, content);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
