package io.github.orhanyarkin.x402.sample;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.Set;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

/**
 * Generates a fresh testnet buyer wallet at {@code secrets/buyer.key} under the repository root.
 *
 * <p>Never touches Spring: a key doesn't exist yet, so there is nothing for {@code
 * X402ClientAutoConfiguration} to bind, and none of the other commands' beans are relevant here.
 * Uses {@code org.web3j:crypto} directly, the same library {@code PrivateKeyPaymentSigner} is built
 * on (ADR-0008).
 */
final class NewWalletCommand {

    private static final String KEY_FILE = "secrets/buyer.key";
    private static final Set<PosixFilePermission> OWNER_READ_WRITE_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_READ_WRITE_EXECUTE_ONLY =
            PosixFilePermissions.fromString("rwx------");

    private NewWalletCommand() {}

    /**
     * @throws IllegalStateException if a key file already exists at {@code secrets/buyer.key}
     *     under {@code repoRoot}; this command never overwrites one
     */
    static void run(Path repoRoot, PrintStream out) throws IOException, GeneralSecurityException {
        Path secretsDir = repoRoot.resolve("secrets");
        // Only applies to a directory this call actually creates: if secrets/ already exists (the
        // common case, since it's git-ignored but typically present), its existing mode is left
        // alone rather than retroactively tightened.
        Files.createDirectories(secretsDir, PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE_EXECUTE_ONLY));
        Path keyFile = repoRoot.resolve(KEY_FILE);

        ECKeyPair keyPair = Keys.createEcKeyPair();
        String privateKeyHex = "0x" + Numeric.toHexStringNoPrefixZeroPadded(keyPair.getPrivateKey(), 64);
        String address = Keys.toChecksumAddress(Keys.getAddress(keyPair));

        // Files.createFile with an explicit posix-permissions attribute creates the file with
        // exactly that mode from the moment it exists -- no window where it is briefly readable by
        // anyone else -- and fails atomically if the file is already there, so "refuse to
        // overwrite" and "0600 from creation" are the same operation, not two separate steps that
        // could race.
        FileAttribute<Set<PosixFilePermission>> ownerOnly = PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE_ONLY);
        try {
            Files.createFile(keyFile, ownerOnly);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("refusing to overwrite an existing key at " + keyFile);
        }
        Files.writeString(keyFile, privateKeyHex);

        out.println("address: " + address);
        out.println("fund it at https://faucet.circle.com/ (Base Sepolia)");
    }
}
