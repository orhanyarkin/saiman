package io.github.orhanyarkin.saiman.orchestrator.budget;

import io.github.orhanyarkin.saiman.modelrouter.RouterProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Startup cross-checks between the orchestrator's limits and the model router's. Rules that would
 * make a limit meaningless fail startup in the properties' constructors; this class only warns about
 * combinations that are safe but surprising.
 *
 * <ul>
 *   <li>{@code saiman.orchestrator.runs.llm-budget-usd-micros} above {@code
 *       saiman.router.max-scope-budget-usd-micros}: the router clamps every scope budget to its
 *       maximum, so a run gets less LLM budget than configured here (spend stays bounded).
 * </ul>
 */
@Component
class LimitsConsistencyCheck {

    private static final Logger LOG = LoggerFactory.getLogger(LimitsConsistencyCheck.class);

    LimitsConsistencyCheck(RunLimitsProperties runs, ObjectProvider<RouterProperties> router) {
        RouterProperties routerProperties = router.getIfAvailable();
        if (routerProperties != null) {
            String warning = llmBudgetWarning(runs.llmBudgetUsdMicros(), routerProperties.maxScopeBudgetUsdMicros());
            if (warning != null) {
                LOG.warn(warning);
            }
        }
    }

    /** The warning for a run LLM budget above the router's scope maximum, or null if it fits. */
    static @Nullable String llmBudgetWarning(long runLlmBudgetUsdMicros, long routerMaxScopeBudgetUsdMicros) {
        if (runLlmBudgetUsdMicros <= routerMaxScopeBudgetUsdMicros) {
            return null;
        }
        return "saiman.orchestrator.runs.llm-budget-usd-micros (" + runLlmBudgetUsdMicros
                + ") is above saiman.router.max-scope-budget-usd-micros (" + routerMaxScopeBudgetUsdMicros
                + "): the router clamps each run's LLM budget to " + routerMaxScopeBudgetUsdMicros;
    }
}
