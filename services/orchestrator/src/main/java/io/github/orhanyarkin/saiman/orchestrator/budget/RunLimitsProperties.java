package io.github.orhanyarkin.saiman.orchestrator.budget;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.orchestrator.runs.*}: execution limits of research runs.
 *
 * @param maxConcurrent runs executing at once; 2 matches the seller's in-flight limit per payer, and
 *     the orchestrator is one wallet (F-D)
 * @param llmBudgetUsdMicros each run's model-spend scope in USD micro-dollars (ADR-0011 amendment)
 * @param deadline wall-clock limit of one run, from the start of its execution: when it passes, a
 *     pending approval wait ends (the approval expires), no further tool call or step starts, and the
 *     run fails with {@code RUN_DEADLINE}
 */
@ConfigurationProperties("saiman.orchestrator.runs")
public record RunLimitsProperties(
        @DefaultValue("2") int maxConcurrent,
        @DefaultValue("150000") long llmBudgetUsdMicros,
        @DefaultValue("15m") Duration deadline) {

    static final Duration MIN_DEADLINE = Duration.ofSeconds(1);
    static final Duration MAX_DEADLINE = Duration.ofHours(24);

    public RunLimitsProperties {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("saiman.orchestrator.runs.max-concurrent must be positive");
        }
        if (llmBudgetUsdMicros <= 0) {
            throw new IllegalArgumentException("saiman.orchestrator.runs.llm-budget-usd-micros must be positive");
        }
        if (deadline.compareTo(MIN_DEADLINE) < 0 || deadline.compareTo(MAX_DEADLINE) > 0) {
            throw new IllegalArgumentException("saiman.orchestrator.runs.deadline must be between 1s and 24h");
        }
    }
}
