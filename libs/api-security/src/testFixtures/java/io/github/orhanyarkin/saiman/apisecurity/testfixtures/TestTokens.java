package io.github.orhanyarkin.saiman.apisecurity.testfixtures;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Known test tokens and their SHA-256 digests, for the services' tests (never for a deployment). Use the {@code
 * *_PROPERTY} constants in {@code @SpringBootTest(properties = ...)}, or {@link #register(DynamicPropertyRegistry)},
 * then send {@code Authorization: }{@link #bearer(String) bearer(TestTokens.OPERATOR)}.
 *
 * <pre>{@code
 * @SpringBootTest(properties = {TestTokens.READER_PROPERTY, TestTokens.OPERATOR_PROPERTY})
 * class RunApiTests { ... header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.READER)) ... }
 * }</pre>
 */
public final class TestTokens {

    /** A READER token. */
    public static final String READER = "saiman-test-reader-token-0123456789abcdefghij";

    /** An OPERATOR token. */
    public static final String OPERATOR = "saiman-test-operator-token-0123456789abcdefghij";

    /** The service token of caller {@code ledger} ({@code SERVICE_ledger}). */
    public static final String SERVICE_LEDGER = "saiman-test-service-ledger-token-0123456789abcdef";

    /** The service token of caller {@code evals} ({@code SERVICE_evals}). */
    public static final String SERVICE_EVALS = "saiman-test-service-evals-token-0123456789abcdefg";

    /** A well-formed token that no role has. */
    public static final String UNKNOWN = "saiman-test-unknown-token-0123456789abcdefghij";

    /** SHA-256 of {@link #READER}. */
    public static final String READER_SHA256 = "bb8a7703e6054c96e9442f822efebaa7c3b1690de33838d7ea49ffbf6f23a8ac";

    /** SHA-256 of {@link #OPERATOR}. */
    public static final String OPERATOR_SHA256 = "064f3fe639ff9d727d0c62769168ce319345c94ea3833c66c792b81aa50f37a3";

    /** SHA-256 of {@link #SERVICE_LEDGER}. */
    public static final String SERVICE_LEDGER_SHA256 =
            "84c219d596b3e201f6829cf73bd4ad3516facd7d7f5c3babb2f799d3ffb6ee46";

    /** SHA-256 of {@link #SERVICE_EVALS}. */
    public static final String SERVICE_EVALS_SHA256 =
            "011b6f777f716526cd7cdfb963234b97d9fe8ae1d6c3c6adb7f1dcfbe4ae9794";

    public static final String READER_PROPERTY = "saiman.auth.reader-token-sha256=" + READER_SHA256;
    public static final String OPERATOR_PROPERTY = "saiman.auth.operator-token-sha256=" + OPERATOR_SHA256;
    public static final String SERVICE_LEDGER_PROPERTY =
            "saiman.auth.service.tokens.ledger.sha256=" + SERVICE_LEDGER_SHA256;
    public static final String SERVICE_EVALS_PROPERTY =
            "saiman.auth.service.tokens.evals.sha256=" + SERVICE_EVALS_SHA256;

    private TestTokens() {}

    /** The value of an {@code Authorization} header carrying {@code token}. */
    public static String bearer(String token) {
        return "Bearer " + token;
    }

    /** SHA-256 of {@code token}, lowercase hex: what a service is configured with. */
    public static String digest(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every test digest as properties: both human roles and both service callers. */
    public static Map<String, String> properties() {
        return Map.of(
                "saiman.auth.reader-token-sha256", READER_SHA256,
                "saiman.auth.operator-token-sha256", OPERATOR_SHA256,
                "saiman.auth.service.tokens.ledger.sha256", SERVICE_LEDGER_SHA256,
                "saiman.auth.service.tokens.evals.sha256", SERVICE_EVALS_SHA256);
    }

    /** Registers {@link #properties()}, e.g. from a {@code @DynamicPropertySource} method. */
    public static void register(DynamicPropertyRegistry registry) {
        properties().forEach((name, value) -> registry.add(name, () -> value));
    }
}
