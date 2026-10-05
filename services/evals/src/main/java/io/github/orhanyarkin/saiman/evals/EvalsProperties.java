package io.github.orhanyarkin.saiman.evals;

import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.evals.*} (design M6 B4). Every key has a default so a bare {@code bootRun} against a local
 * stack works; compose overrides the URLs, the output directory and the service token.
 *
 * @param runOnStartup run the eval when the application starts (tests switch it off)
 * @param goldenSet Spring resource location of the golden set
 * @param outputDir where {@code latest.md}, {@code latest.json} and {@code runs/} are written
 * @param label free text shown in the report header and the run file name (e.g. {@code baseline})
 * @param gitSha commit the evaluated stack was built from; the environment variable {@code GIT_SHA} is the usual source
 */
@ConfigurationProperties("saiman.evals")
public record EvalsProperties(
        @DefaultValue("false") boolean runOnStartup,
        @DefaultValue("classpath:golden/golden-set.v1.yaml") String goldenSet,
        @DefaultValue("/out") String outputDir,
        @DefaultValue("") String label,
        @DefaultValue("unknown") String gitSha,
        @DefaultValue Ingest ingest,
        @DefaultValue Seller seller,
        @DefaultValue Retrieval retrieval,
        @DefaultValue Answers answers) {

    /**
     * @param baseUrl ingest base URL (its {@code /internal} Host allowlist must accept the host used here)
     * @param readTimeout per-attempt timeout
     * @param retryAttempts total attempts per query (transport errors and 5xx only)
     * @param retryWait first back-off, doubled with jitter
     */
    public record Ingest(
            @DefaultValue("http://localhost:8083") String baseUrl,
            @DefaultValue("20s") Duration readTimeout,
            @DefaultValue("3") int retryAttempts,
            @DefaultValue("500ms") Duration retryWait) {}

    /**
     * Tier A: seller-api base URL and the evals service token (a secret; never logged).
     *
     * @param baseUrl seller-api, e.g. {@code http://seller-api:8081}; required when the answer tier is on
     * @param serviceToken from the configtree secret {@code saiman.evals.seller.service-token}; surrounding
     *     whitespace (a trailing newline) is stripped; {@link #requireServiceToken()} checks it
     * @param connectTimeout TCP connect timeout
     * @param readTimeout per-question timeout (one LLM call)
     * @param retryAttempts total attempts per question (connect failures and 5xx only, never 4xx or a read timeout)
     * @param retryWait first back-off, doubled with jitter
     */
    public record Seller(
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String serviceToken,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("90s") Duration readTimeout,
            @DefaultValue("2") int retryAttempts,
            @DefaultValue("1s") Duration retryWait) {

        /** The shape every API token has (libs/api-security {@code StaticTokenIntrospector}). */
        private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");

        public Seller {
            serviceToken = serviceToken.strip();
        }

        /** The token; throws with a message that never quotes it. */
        public String requireServiceToken() {
            if (serviceToken.isEmpty()) {
                throw new IllegalStateException("saiman.evals.answers.enabled=true needs"
                        + " saiman.evals.seller.service-token (secret seller_service_token_evals)");
            }
            if (!TOKEN.matcher(serviceToken).matches()) {
                throw new IllegalStateException("saiman.evals.seller.service-token is malformed"
                        + " (expected 32-128 characters of [A-Za-z0-9_-])");
            }
            return serviceToken;
        }

        @Override
        public String toString() {
            return "Seller[baseUrl=" + baseUrl + ", serviceToken=***]";
        }
    }

    /** @param topK chunks requested per query (the ingest contract allows 1-20) */
    public record Retrieval(@DefaultValue("10") int topK) {}

    /** Tier A switches (T6b). */
    public record Answers(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("30") int maxQuestions,
            @DefaultValue("true") boolean stopOnCap) {}
}
