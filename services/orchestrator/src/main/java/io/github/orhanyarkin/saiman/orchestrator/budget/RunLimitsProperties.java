package io.github.orhanyarkin.saiman.orchestrator.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.orchestrator.runs.*}: execution limits of research runs.
 *
 * @param maxConcurrent runs executing at once; 2 matches the seller's in-flight limit per payer, and
 *     the orchestrator is one wallet (F-D)
 * @param llmBudgetUsdMicros each run's model-spend scope in USD micro-dollars (ADR-0011 amendment)
 */
@ConfigurationProperties("saiman.orchestrator.runs")
public record RunLimitsProperties(
        @DefaultValue("2") int maxConcurrent,
        @DefaultValue("150000") long llmBudgetUsdMicros) {

    public RunLimitsProperties {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("saiman.orchestrator.runs.max-concurrent must be positive");
        }
        if (llmBudgetUsdMicros <= 0) {
            throw new IllegalArgumentException("saiman.orchestrator.runs.llm-budget-usd-micros must be positive");
        }
    }
}
