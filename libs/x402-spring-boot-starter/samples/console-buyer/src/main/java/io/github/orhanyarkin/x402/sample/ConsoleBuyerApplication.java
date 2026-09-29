package io.github.orhanyarkin.x402.sample;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A standalone console client for the x402 starter (docs/design/m1-x402.md "Console buyer and make
 * targets"): {@code buy}, {@code replay}, {@code new-wallet}, {@code testnet-check}.
 *
 * <p>Never uses an embedded web server: this depends only on {@code spring-boot-starter-restclient}
 * (no servlet/reactive web classes on the classpath), so Boot infers {@code
 * WebApplicationType.NONE} automatically — "zero config beyond properties" needs no explicit wiring
 * for that. Every {@code x402.client.*} value except the private key is declared in {@code
 * application.yaml}, resolved the normal Spring way (a property, or a {@code ${...}} placeholder
 * against the environment); see that file's comments, including why {@code
 * spring.http.clients.redirects: dont-follow} is there. The private key is the one property this
 * class still resolves in code: {@code X402_BUYER_PRIVATE_KEY} does not match Boot's
 * relaxed-binding name for {@code x402.client.private-key} (that would be {@code
 * X402_CLIENT_PRIVATE_KEY}), and the {@code secrets/buyer.key} file fallback needs the repository
 * root, which a property file cannot resolve at runtime.
 */
@SpringBootApplication
public class ConsoleBuyerApplication {

    private static final Pattern ADDRESS_PATTERN = Pattern.compile("0x[0-9a-fA-F]{40}");

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        ApplicationArguments arguments = new DefaultApplicationArguments(args);
        List<String> commands = arguments.getNonOptionArgs();
        if (commands.isEmpty()) {
            System.err.println("usage: buy --url=<url> | replay --url=<url> | new-wallet | testnet-check");
            return 2;
        }
        String command = commands.get(0);

        if ("new-wallet".equals(command)) {
            // No x402 client beans are relevant (and none would exist yet: there is no key until
            // this command creates one), so this never starts a Spring context, and always needs
            // the repository root (to write secrets/buyer.key there).
            Path repoRoot;
            try {
                repoRoot = RepoRoot.locate();
            } catch (IllegalStateException e) {
                System.err.println(e.getMessage());
                return 2;
            }
            try {
                NewWalletCommand.run(repoRoot, System.out);
                return 0;
            } catch (IllegalStateException | IOException | GeneralSecurityException e) {
                System.err.println("new-wallet failed: " + e.getMessage());
                return 1;
            }
        }

        if (!sellerPayToFormatIsValidIfSet()) {
            System.err.println("X402_SELLER_PAYTO_ADDRESS must be a 0x-prefixed 20-byte hex address");
            return 2;
        }

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ConsoleBuyerApplication.class)
                .properties(privateKeyProperty())
                .run()) {
            return switch (command) {
                case "buy" ->
                    context.getBean(BuyCommand.class).run(requireUrl(arguments), System.out, System.err) ? 0 : 1;
                case "replay" ->
                    context.getBean(ReplayCommand.class).run(requireUrl(arguments), System.out, System.err) ? 0 : 1;
                case "testnet-check" ->
                    context.getBean(TestnetCheckCommand.class).run(System.out, System.err) ? 0 : 1;
                default -> {
                    System.err.println("unknown command: " + command);
                    yield 2;
                }
            };
        } catch (Exception e) {
            // Every exception this sample and the starter itself throw already omits secret and
            // wire-payload content from getMessage() (see e.g. PaymentRejectedException,
            // SpendDeniedException, X402CodecException); printing it here is safe.
            System.err.println(command + " failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Resolves {@code x402.client.private-key} from the environment first, only resolving the
     * repository root (and therefore only ever touching the filesystem under {@code secrets/}) if
     * {@code X402_BUYER_PRIVATE_KEY} is unset — {@code buy}/{@code replay}/{@code testnet-check}
     * against an env-var-provided key never require running inside a saiman checkout at all.
     *
     * @throws IllegalStateException (via {@link RepoRoot#locate()}) if the environment variable is
     *     unset and this process is not running inside a saiman checkout
     */
    private static Map<String, Object> privateKeyProperty() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Optional<String> key = BuyerKeySource.fromEnvironment();
        if (key.isEmpty()) {
            key = BuyerKeySource.fromFile(RepoRoot.locate());
        }
        key.ifPresent(value -> properties.put("x402.client.private-key", value));
        return properties;
    }

    /**
     * {@code X402_SELLER_PAYTO_ADDRESS} is a public wallet address (ADR-0009), not a secret, but
     * this still never echoes a malformed value back: consistent with every other validation
     * message in this starter, the message states the rule, not the rejected input.
     */
    private static boolean sellerPayToFormatIsValidIfSet() {
        String value = System.getenv("X402_SELLER_PAYTO_ADDRESS");
        return value == null
                || value.isBlank()
                || ADDRESS_PATTERN.matcher(value.trim()).matches();
    }

    private static String requireUrl(ApplicationArguments arguments) {
        List<String> values = arguments.getOptionValues("url");
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            throw new IllegalArgumentException("--url is required");
        }
        return values.get(0);
    }
}
