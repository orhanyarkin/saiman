package io.github.orhanyarkin.saiman.evals;

import java.time.Duration;
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

    /** Tier A (T6b): seller-api base URL and the evals service token (a secret; never logged). */
    public record Seller(
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String serviceToken) {

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
