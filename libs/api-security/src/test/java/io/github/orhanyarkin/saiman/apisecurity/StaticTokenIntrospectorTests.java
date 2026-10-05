package io.github.orhanyarkin.saiman.apisecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;

class StaticTokenIntrospectorTests {

    private static ApiTokenProperties props(
            boolean enabled,
            boolean requireHumanTokens,
            List<String> readers,
            List<String> operators,
            Map<String, ApiTokenProperties.ServiceToken> services) {
        return new ApiTokenProperties(
                enabled, requireHumanTokens, readers, operators, new ApiTokenProperties.Service(services));
    }

    private static ApiTokenProperties allTestTokens() {
        return props(
                true,
                true,
                List.of(TestTokens.READER_SHA256),
                List.of(TestTokens.OPERATOR_SHA256),
                Map.of(
                        "ledger", new ApiTokenProperties.ServiceToken(TestTokens.SERVICE_LEDGER_SHA256),
                        "evals", new ApiTokenProperties.ServiceToken(TestTokens.SERVICE_EVALS_SHA256)));
    }

    /** Counts calls and records which expected digests were compared, delegating to the real comparison. */
    private static final class CountingComparator implements StaticTokenIntrospector.DigestComparator {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> compared = new ArrayList<>();

        @Override
        public boolean equal(byte[] expected, byte[] actual) {
            calls.incrementAndGet();
            compared.add(HexFormat.of().formatHex(expected));
            return MessageDigest.isEqual(expected, actual);
        }
    }

    private static List<String> authorities(OAuth2AuthenticatedPrincipal principal) {
        return principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    @Test
    void humanTokensAuthenticateAsTheirRoleWithDigestPrefixedNames() {
        StaticTokenIntrospector introspector = StaticTokenIntrospector.fromProperties(allTestTokens());

        OAuth2AuthenticatedPrincipal reader = introspector.introspect(TestTokens.READER);
        assertThat(reader.getName()).isEqualTo("reader:" + TestTokens.READER_SHA256.substring(0, 8));
        assertThat(reader.<List<String>>getAttribute(StaticTokenIntrospector.ROLES_ATTRIBUTE))
                .containsExactly("READER");
        assertThat(authorities(reader)).containsExactly("ROLE_READER");

        OAuth2AuthenticatedPrincipal operator = introspector.introspect(TestTokens.OPERATOR);
        assertThat(operator.getName()).isEqualTo("operator:064f3fe6");
        assertThat(operator.<List<String>>getAttribute(StaticTokenIntrospector.ROLES_ATTRIBUTE))
                .containsExactly("OPERATOR");
        assertThat(authorities(operator)).containsExactly("ROLE_OPERATOR");
    }

    @Test
    void serviceTokensCarryTheRoleAndTheCallerAuthority() {
        StaticTokenIntrospector introspector = StaticTokenIntrospector.fromProperties(allTestTokens());

        OAuth2AuthenticatedPrincipal ledger = introspector.introspect(TestTokens.SERVICE_LEDGER);
        assertThat(ledger.getName()).isEqualTo("service-ledger:84c219d5");
        assertThat(ledger.<List<String>>getAttribute(StaticTokenIntrospector.ROLES_ATTRIBUTE))
                .containsExactly("SERVICE");
        assertThat(ledger.<String>getAttribute(StaticTokenIntrospector.CALLER_ATTRIBUTE))
                .isEqualTo("ledger");
        assertThat(authorities(ledger)).containsExactlyInAnyOrder("ROLE_SERVICE", "SERVICE_ledger");

        assertThat(authorities(introspector.introspect(TestTokens.SERVICE_EVALS)))
                .containsExactlyInAnyOrder("ROLE_SERVICE", "SERVICE_evals");
    }

    @Test
    void everyDigestIsComparedWhetherTheTokenMatchesFirstLastOrNothing() {
        CountingComparator comparator = new CountingComparator();
        StaticTokenIntrospector introspector = StaticTokenIntrospector.fromProperties(allTestTokens(), comparator);
        assertThat(introspector.digestCount()).isEqualTo(4);
        List<String> all = List.of(
                TestTokens.READER_SHA256,
                TestTokens.OPERATOR_SHA256,
                TestTokens.SERVICE_EVALS_SHA256,
                TestTokens.SERVICE_LEDGER_SHA256);

        // Entry order: readers, operators, then callers sorted by name, so READER is first and ledger is last.
        for (String token : List.of(TestTokens.READER, TestTokens.SERVICE_LEDGER, TestTokens.OPERATOR)) {
            comparator.calls.set(0);
            comparator.compared.clear();
            introspector.introspect(token);
            assertThat(comparator.calls).hasValue(4);
            assertThat(comparator.compared).containsExactlyElementsOf(all);
        }

        comparator.calls.set(0);
        comparator.compared.clear();
        assertThatThrownBy(() -> introspector.introspect(TestTokens.UNKNOWN))
                .isInstanceOf(BadOpaqueTokenException.class);
        assertThat(comparator.calls).hasValue(4);
        assertThat(comparator.compared).containsExactlyElementsOf(all);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "short-token-0123456789abcdefghi", // 31 characters
                "saiman.test.reader.token.0123456789abcdef", // dot
                "saiman+test/reader=token0123456789abcdefgh", // base64 (not url-safe) characters
                "saiman test reader token 0123456789abcdefgh", // spaces
                "saiman-test-reader-token-0123456789abcdefgh\n", // trailing newline
                "saiman-test-reader-token-0123456789abcdefghç", // non-ASCII
            })
    void malformedTokensAreRejectedBeforeHashing(String token) {
        CountingComparator comparator = new CountingComparator();
        StaticTokenIntrospector introspector = StaticTokenIntrospector.fromProperties(allTestTokens(), comparator);

        assertThatThrownBy(() -> introspector.introspect(token))
                .isInstanceOf(BadOpaqueTokenException.class)
                .hasMessage("Invalid bearer token");
        assertThat(comparator.calls).hasValue(0);
    }

    @Test
    void lengthBoundsAreInclusive() {
        CountingComparator comparator = new CountingComparator();
        StaticTokenIntrospector introspector = StaticTokenIntrospector.fromProperties(allTestTokens(), comparator);

        for (String token : List.of("a".repeat(32), "a".repeat(128))) {
            assertThatThrownBy(() -> introspector.introspect(token)).isInstanceOf(BadOpaqueTokenException.class);
        }
        assertThat(comparator.calls).hasValue(8); // both well formed: hashed and compared with all four digests

        comparator.calls.set(0);
        for (String token : List.of("a".repeat(129), "a".repeat(100_000))) {
            assertThatThrownBy(() -> introspector.introspect(token)).isInstanceOf(BadOpaqueTokenException.class);
        }
        assertThat(comparator.calls).hasValue(0);
    }

    @Test
    void failureMessagesNeverContainTheToken() {
        StaticTokenIntrospector introspector = StaticTokenIntrospector.fromProperties(allTestTokens());
        for (String token : List.of(TestTokens.UNKNOWN, "planted.secret.token.0123456789abcdef")) {
            assertThatThrownBy(() -> introspector.introspect(token))
                    .isInstanceOf(BadOpaqueTokenException.class)
                    .hasMessage("Invalid bearer token")
                    .hasNoCause();
        }
    }

    @Test
    void humanDigestsAreRequiredByDefault() {
        ApiTokenProperties noReader = props(true, true, List.of(), List.of(TestTokens.OPERATOR_SHA256), Map.of());
        assertThatThrownBy(() -> StaticTokenIntrospector.fromProperties(noReader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saiman.auth.reader-token-sha256 has no digest")
                .hasMessageContaining("SAIMAN_AUTH_READER_TOKEN_SHA256");

        ApiTokenProperties noOperator = props(true, true, List.of(TestTokens.READER_SHA256), List.of(), Map.of());
        assertThatThrownBy(() -> StaticTokenIntrospector.fromProperties(noOperator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saiman.auth.operator-token-sha256 has no digest")
                .hasMessageContaining("SAIMAN_AUTH_OPERATOR_TOKEN_SHA256");
    }

    @Test
    void aServiceOnlyApplicationNeedsNoHumanDigests() {
        ApiTokenProperties serviceOnly = props(
                true,
                false,
                List.of(),
                List.of(),
                Map.of("ledger", new ApiTokenProperties.ServiceToken(TestTokens.SERVICE_LEDGER_SHA256)));
        StaticTokenIntrospector introspector = StaticTokenIntrospector.fromProperties(serviceOnly);

        assertThat(introspector.introspect(TestTokens.SERVICE_LEDGER).getName()).startsWith("service-ledger:");
        assertThatThrownBy(() -> introspector.introspect(TestTokens.READER))
                .isInstanceOf(BadOpaqueTokenException.class);
    }

    @Test
    void aCallerWithoutADigestIsDeniedNotAStartupFailure() {
        ApiTokenProperties evalsUnset = props(
                true,
                false,
                List.of(),
                List.of(),
                Map.of(
                        "ledger", new ApiTokenProperties.ServiceToken(TestTokens.SERVICE_LEDGER_SHA256),
                        "evals", new ApiTokenProperties.ServiceToken(null),
                        "other", new ApiTokenProperties.ServiceToken(" ")));
        StaticTokenIntrospector introspector = StaticTokenIntrospector.fromProperties(evalsUnset);

        assertThat(introspector.digestCount()).isEqualTo(1);
        assertThatThrownBy(() -> introspector.introspect(TestTokens.SERVICE_EVALS))
                .isInstanceOf(BadOpaqueTokenException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "BB8A7703E6054C96E9442F822EFEBAA7C3B1690DE33838D7EA49FFBF6F23A8AC", // uppercase
                "bb8a7703e6054c96e9442f822efebaa7c3b1690de33838d7ea49ffbf6f23a8a", // 63 characters
                "bb8a7703e6054c96e9442f822efebaa7c3b1690de33838d7ea49ffbf6f23a8ac0", // 65 characters
                " bb8a7703e6054c96e9442f822efebaa7c3b1690de33838d7ea49ffbf6f23a8a", // leading space
                "saiman-test-reader-token-0123456789abcdefghij", // a raw token pasted by mistake
                ""
            })
    void malformedDigestsFailStartupWithoutEchoingTheValue(String digest) {
        ApiTokenProperties properties = props(
                true, true, List.of(TestTokens.READER_SHA256, digest), List.of(TestTokens.OPERATOR_SHA256), Map.of());

        assertThatThrownBy(() -> StaticTokenIntrospector.fromProperties(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("saiman.auth.reader-token-sha256[1] is not a SHA-256 digest")
                .satisfies(e -> {
                    if (!digest.isBlank()) {
                        assertThat(e.getMessage()).doesNotContain(digest.trim());
                    }
                });
    }

    @Test
    void aMalformedServiceDigestFailsStartup() {
        ApiTokenProperties properties = props(
                true,
                false,
                List.of(),
                List.of(),
                Map.of("ledger", new ApiTokenProperties.ServiceToken(TestTokens.SERVICE_LEDGER)));

        assertThatThrownBy(() -> StaticTokenIntrospector.fromProperties(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("saiman.auth.service.tokens.ledger.sha256 is not a SHA-256 digest")
                .message()
                .doesNotContain(TestTokens.SERVICE_LEDGER);
    }

    @Test
    void oneDigestInTwoRolesFailsStartup() {
        ApiTokenProperties readerIsOperator =
                props(true, true, List.of(TestTokens.READER_SHA256), List.of(TestTokens.READER_SHA256), Map.of());
        assertThatThrownBy(() -> StaticTokenIntrospector.fromProperties(readerIsOperator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("saiman.auth.operator-token-sha256[0] repeats a digest");

        ApiTokenProperties serviceIsOperator = props(
                true,
                true,
                List.of(TestTokens.READER_SHA256),
                List.of(TestTokens.OPERATOR_SHA256),
                Map.of("ledger", new ApiTokenProperties.ServiceToken(TestTokens.OPERATOR_SHA256)));
        assertThatThrownBy(() -> StaticTokenIntrospector.fromProperties(serviceIsOperator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("saiman.auth.service.tokens.ledger.sha256 repeats a digest");

        ApiTokenProperties twoCallers = props(
                true,
                false,
                List.of(),
                List.of(),
                Map.of(
                        "ledger", new ApiTokenProperties.ServiceToken(TestTokens.SERVICE_LEDGER_SHA256),
                        "evals", new ApiTokenProperties.ServiceToken(TestTokens.SERVICE_LEDGER_SHA256)));
        assertThatThrownBy(() -> StaticTokenIntrospector.fromProperties(twoCallers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repeats a digest");

        ApiTokenProperties sameListTwice = props(
                true,
                true,
                List.of(TestTokens.READER_SHA256, TestTokens.READER_SHA256),
                List.of(TestTokens.OPERATOR_SHA256),
                Map.of());
        assertThatThrownBy(() -> StaticTokenIntrospector.fromProperties(sameListTwice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("saiman.auth.reader-token-sha256[1] repeats a digest");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ledger", "ledger-api", "ledger_api", "1ledger", ""})
    void invalidCallerNamesFailStartup(String caller) {
        ApiTokenProperties properties = props(
                true,
                false,
                List.of(),
                List.of(),
                Map.of(caller, new ApiTokenProperties.ServiceToken(TestTokens.SERVICE_LEDGER_SHA256)));

        assertThatThrownBy(() -> StaticTokenIntrospector.fromProperties(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a valid caller name");
    }

    @Test
    void propertiesToStringShowsCountsNotValues() {
        assertThat(allTestTokens().toString())
                .doesNotContain(TestTokens.READER_SHA256, TestTokens.SERVICE_LEDGER_SHA256)
                .contains("readerDigests=1", "operatorDigests=1", "[evals, ledger]");
    }
}
