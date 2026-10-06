package io.github.orhanyarkin.saiman.apisecurity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

/**
 * Verifies static role tokens against configured SHA-256 digests (ADR-0023). The only class that knows the tokens are
 * static: replacing it (or {@code opaqueToken} with {@code jwt} in {@link SaimanResourceServer}) changes the mechanism
 * without touching request rules or controllers.
 *
 * <ol>
 *   <li>The token must match {@code ^[A-Za-z0-9_-]{32,128}$} (checked before hashing, so nothing else is hashed).
 *   <li>Its SHA-256 is compared with <em>every</em> configured digest using {@link MessageDigest#isEqual}, with no early
 *       exit: the time taken does not depend on which digest matched, or whether one did.
 *   <li>On a match the principal is named {@code <role>:<first 8 hex of the digest>} ({@code reader:1a2b3c4d}, {@code
 *       operator:…}, {@code service-ledger:…}), has the attribute {@code roles} (unprefixed {@link SaimanRole} names)
 *       and the authorities {@code ROLE_<role>} (plus {@code SERVICE_<caller>} for a service token; service principals
 *       also carry the attribute {@code caller}).
 * </ol>
 *
 * Failures throw {@link BadOpaqueTokenException} with one fixed message. The token is never logged and never part of
 * an exception message.
 */
public final class StaticTokenIntrospector implements OpaqueTokenIntrospector {

    /** Attribute holding the principal's unprefixed role names, e.g. {@code ["OPERATOR"]}. */
    public static final String ROLES_ATTRIBUTE = "roles";

    /** Attribute holding a service principal's caller name, e.g. {@code ledger}. */
    public static final String CALLER_ATTRIBUTE = "caller";

    static final int MIN_TOKEN_LENGTH = 32;
    static final int MAX_TOKEN_LENGTH = 128;
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{32,128}$");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final String INVALID = "Invalid bearer token";
    private static final Logger log = LoggerFactory.getLogger(StaticTokenIntrospector.class);

    /** Compares two digests; {@link MessageDigest#isEqual} in production, a counting spy in tests. */
    @FunctionalInterface
    interface DigestComparator {
        boolean equal(byte[] expected, byte[] actual);
    }

    /** One configured digest and the principal a matching token authenticates as. */
    private static final class Entry {
        private final byte[] digest;
        private final OAuth2AuthenticatedPrincipal principal;

        Entry(byte[] digest, OAuth2AuthenticatedPrincipal principal) {
            this.digest = digest.clone();
            this.principal = principal;
        }

        byte[] digest() {
            return digest;
        }

        OAuth2AuthenticatedPrincipal principal() {
            return principal;
        }
    }

    private final List<Entry> entries;
    private final DigestComparator comparator;

    private StaticTokenIntrospector(List<Entry> entries, DigestComparator comparator) {
        this.entries = List.copyOf(entries);
        this.comparator = comparator;
    }

    /**
     * Builds the introspector from validated properties.
     *
     * @throws IllegalStateException on a malformed or duplicate digest, a malformed caller name, or (with {@code
     *     require-human-tokens}) a missing READER or OPERATOR digest; the message names the property, never the value
     */
    public static StaticTokenIntrospector fromProperties(ApiTokenProperties properties) {
        return fromProperties(properties, MessageDigest::isEqual);
    }

    static StaticTokenIntrospector fromProperties(ApiTokenProperties properties, DigestComparator comparator) {
        if (properties.requireHumanTokens()) {
            requireNonEmpty(properties.readerTokenSha256(), "saiman.auth.reader-token-sha256");
            requireNonEmpty(properties.operatorTokenSha256(), "saiman.auth.operator-token-sha256");
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addHuman(entries, seen, properties.readerTokenSha256(), SaimanRole.READER, "saiman.auth.reader-token-sha256");
        addHuman(
                entries,
                seen,
                properties.operatorTokenSha256(),
                SaimanRole.OPERATOR,
                "saiman.auth.operator-token-sha256");
        int humanCount = entries.size();
        // Sorted so that the startup log and the entry order are deterministic.
        for (Map.Entry<String, ApiTokenProperties.ServiceToken> service :
                new TreeMap<>(properties.serviceTokens()).entrySet()) {
            String caller = service.getKey();
            String property = "saiman.auth.service.tokens." + caller + ".sha256";
            if (!SaimanAuthorities.CALLER.matcher(caller).matches()) {
                throw new IllegalStateException("A key under saiman.auth.service.tokens is not a valid caller name"
                        + " (lowercase letters and digits, starting with a letter)");
            }
            String digest = service.getValue().sha256();
            if (digest == null || digest.isBlank()) {
                log.warn("{} is not set: every request as service caller '{}' is denied", property, caller);
                continue;
            }
            byte[] bytes = parseDigest(digest, property, seen);
            entries.add(new Entry(
                    bytes,
                    principal(
                            "service-" + caller + ":" + digest.substring(0, 8),
                            Map.of(ROLES_ATTRIBUTE, List.of(SaimanRole.SERVICE.name()), CALLER_ATTRIBUTE, caller),
                            List.of(
                                    SaimanRole.SERVICE.grantedAuthority(),
                                    new SimpleGrantedAuthority(SaimanAuthorities.service(caller))))));
        }
        log.info(
                "Static API tokens: {} READER, {} OPERATOR and {} service digest(s)",
                properties.readerTokenSha256().size(),
                properties.operatorTokenSha256().size(),
                entries.size() - humanCount);
        return new StaticTokenIntrospector(entries, comparator);
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(@Nullable String token) {
        if (token == null
                || token.length() < MIN_TOKEN_LENGTH
                || token.length() > MAX_TOKEN_LENGTH
                || !TOKEN.matcher(token).matches()) {
            throw new BadOpaqueTokenException(INVALID);
        }
        byte[] actual = sha256(token);
        @Nullable OAuth2AuthenticatedPrincipal match = null;
        for (Entry entry : entries) {
            // No early exit: every digest is compared, whatever matched before.
            boolean equal = comparator.equal(entry.digest(), actual);
            if (equal) {
                match = entry.principal();
            }
        }
        if (match == null) {
            throw new BadOpaqueTokenException(INVALID);
        }
        return match;
    }

    /** Number of configured digests (every one of them is compared on each request). */
    int digestCount() {
        return entries.size();
    }

    private static void addHuman(
            List<Entry> entries, Set<String> seen, List<String> digests, SaimanRole role, String property) {
        for (int i = 0; i < digests.size(); i++) {
            String digest = digests.get(i);
            byte[] bytes = parseDigest(digest, property + "[" + i + "]", seen);
            entries.add(new Entry(
                    bytes,
                    principal(
                            role.name().toLowerCase(Locale.ROOT) + ":" + digest.substring(0, 8),
                            Map.of(ROLES_ATTRIBUTE, List.of(role.name())),
                            List.of(role.grantedAuthority()))));
        }
    }

    private static byte[] parseDigest(String digest, String property, Set<String> seen) {
        if (!DIGEST.matcher(digest).matches()) {
            // Never echo the value: the likeliest mistake is a raw token pasted into a digest variable.
            throw new IllegalStateException(
                    property + " is not a SHA-256 digest (64 lowercase hex characters); the value is not shown");
        }
        if (!seen.add(digest)) {
            throw new IllegalStateException(property
                    + " repeats a digest that is already configured (in another role, another caller or the same"
                    + " list); every token must belong to exactly one role");
        }
        return HexFormat.of().parseHex(digest);
    }

    private static void requireNonEmpty(List<String> digests, String property) {
        if (digests.isEmpty()) {
            throw new IllegalStateException(property + " has no digest. Set it (environment variable "
                    + property.replace("saiman.auth.", "SAIMAN_AUTH_")
                            .replace('.', '_')
                            .replace('-', '_')
                            .toUpperCase(Locale.ROOT)
                    + ") or saiman.auth.require-human-tokens=false for a service without human callers");
        }
    }

    private static OAuth2AuthenticatedPrincipal principal(
            String name, Map<String, Object> attributes, List<GrantedAuthority> authorities) {
        return new OAuth2IntrospectionAuthenticatedPrincipal(name, attributes, authorities);
    }

    private static byte[] sha256(String token) {
        try {
            // A new instance per call: MessageDigest is not thread-safe.
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
