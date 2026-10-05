package io.github.orhanyarkin.saiman.apisecurity;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API token configuration, {@code saiman.auth.*} (ADR-0023). Services hold only <b>SHA-256 digests</b> of the tokens
 * (lowercase hex, 64 characters), never the raw tokens; a digest is not a secret.
 *
 * <table>
 *   <caption>Properties and their environment variables</caption>
 *   <tr><th>Property</th><th>Environment variable</th><th>Default</th></tr>
 *   <tr><td>{@code saiman.auth.enabled}</td><td>{@code SAIMAN_AUTH_ENABLED}</td><td>{@code true}</td></tr>
 *   <tr><td>{@code saiman.auth.require-human-tokens}</td><td>{@code SAIMAN_AUTH_REQUIRE_HUMAN_TOKENS}</td>
 *       <td>{@code true}</td></tr>
 *   <tr><td>{@code saiman.auth.reader-token-sha256} (list)</td><td>{@code SAIMAN_AUTH_READER_TOKEN_SHA256}
 *       (comma-separated)</td><td>empty</td></tr>
 *   <tr><td>{@code saiman.auth.operator-token-sha256} (list)</td><td>{@code SAIMAN_AUTH_OPERATOR_TOKEN_SHA256}
 *       (comma-separated)</td><td>empty</td></tr>
 *   <tr><td>{@code saiman.auth.service.tokens.ledger.sha256}</td>
 *       <td>{@code SAIMAN_AUTH_SERVICE_TOKENS_LEDGER_SHA256}</td><td>unset</td></tr>
 *   <tr><td>{@code saiman.auth.service.tokens.evals.sha256}</td>
 *       <td>{@code SAIMAN_AUTH_SERVICE_TOKENS_EVALS_SHA256}</td><td>unset</td></tr>
 * </table>
 *
 * <p>Validation happens when the {@link StaticTokenIntrospector} is built (startup):
 *
 * <ul>
 *   <li>Every digest must be 64 lowercase hex characters, and no digest may appear twice (in two roles, two callers,
 *       or twice in one list).
 *   <li>With {@code require-human-tokens=true} (the default; the orchestrator and ledger) both {@code
 *       reader-token-sha256} and {@code operator-token-sha256} need at least one digest. seller-api sets it to {@code
 *       false} and uses service tokens only.
 *   <li>A configured service caller without a digest only logs a warning: requests as that caller are then denied.
 * </ul>
 *
 * @param enabled whether the auto-configuration creates the token introspector and role hierarchy at all
 * @param requireHumanTokens whether READER and OPERATOR digests are mandatory
 * @param readerTokenSha256 digests of READER tokens (more than one allows a rotation overlap)
 * @param operatorTokenSha256 digests of OPERATOR tokens
 * @param service the service callers' tokens ({@code saiman.auth.service.tokens.<caller>.sha256})
 */
@ConfigurationProperties("saiman.auth")
public record ApiTokenProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean requireHumanTokens,
        @DefaultValue List<String> readerTokenSha256,
        @DefaultValue List<String> operatorTokenSha256,
        @DefaultValue Service service) {

    public ApiTokenProperties {
        readerTokenSha256 = readerTokenSha256 == null ? List.of() : List.copyOf(readerTokenSha256);
        operatorTokenSha256 = operatorTokenSha256 == null ? List.of() : List.copyOf(operatorTokenSha256);
        service = service == null ? new Service(Map.of()) : service;
    }

    /** Shortcut for {@code service().tokens()}: one entry per service caller, keyed by caller name. */
    public Map<String, ServiceToken> serviceTokens() {
        return service.tokens();
    }

    /** Counts only: digests are not secrets, but a raw token pasted by mistake into a digest property would be. */
    @Override
    public String toString() {
        return "ApiTokenProperties[enabled=" + enabled + ", requireHumanTokens=" + requireHumanTokens
                + ", readerDigests="
                + readerTokenSha256.size() + ", operatorDigests=" + operatorTokenSha256.size() + ", serviceCallers="
                + serviceTokens().keySet().stream().sorted().toList() + "]";
    }

    /**
     * The service callers, {@code saiman.auth.service.*}. A nested object rather than a dashed {@code service-tokens}
     * map: Spring Boot binds a map from environment variables only when no segment above it has a dash, and the
     * documented variables are {@code SAIMAN_AUTH_SERVICE_TOKENS_<CALLER>_SHA256}.
     *
     * @param tokens one entry per service caller, keyed by caller name (lowercase letters and digits)
     */
    public record Service(@DefaultValue Map<String, ServiceToken> tokens) {

        public Service {
            tokens = tokens == null ? Map.of() : Map.copyOf(tokens);
        }
    }

    /**
     * One service caller's token digest.
     *
     * @param sha256 the digest; unset or blank means every request as this caller is denied
     */
    public record ServiceToken(@Nullable String sha256) {

        /** Never prints the value (see {@link ApiTokenProperties#toString()}). */
        @Override
        public String toString() {
            return "ServiceToken[sha256=" + (sha256 == null || sha256.isBlank() ? "unset" : "set") + "]";
        }
    }
}
