package io.github.orhanyarkin.x402.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code PaymentSigner} is a public bean, so any code that injects it can sign without going through
 * {@link SpendGuard}. Inside the starter the only users are {@link X402PaymentInterceptor} (package
 * {@code client}), the signer types themselves ({@code evm}) and the auto-configuration that
 * creates and wires the bean. A new dependency elsewhere in the starter fails this test. (The
 * residual for applications is documented in the threat model's known gaps.)
 */
class PaymentSignerConfinementTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java/io/github/orhanyarkin/x402");
    private static final Pattern REFERENCE = Pattern.compile("\\bPaymentSigner\\b|\\bPrivateKeyPaymentSigner\\b");
    private static final List<String> ALLOWED_PREFIXES =
            List.of("client/", "evm/", "autoconfigure/X402ClientAutoConfiguration.java");

    @Test
    void noStarterClassOutsideTheClientPackageDependsOnPaymentSigner() throws IOException {
        assertThat(MAIN_SOURCES).isDirectory();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String relative = MAIN_SOURCES.relativize(p).toString().replace('\\', '/');
                        return ALLOWED_PREFIXES.stream().noneMatch(relative::startsWith);
                    })
                    .filter(PaymentSignerConfinementTest::mentionsSigner)
                    .map(Path::toString)
                    .toList();
            assertThat(offenders).isEmpty();
        }
    }

    private static boolean mentionsSigner(Path file) {
        try {
            return REFERENCE.matcher(Files.readString(file)).find();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
