package io.github.orhanyarkin.saiman.orchestrator.budget;

import io.github.orhanyarkin.saiman.modelrouter.RouterProperties;
import io.github.orhanyarkin.saiman.orchestrator.payment.SellerProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Startup cross-checks between the orchestrator's limits and those of its neighbours. Rules that
 * would make a limit meaningless fail startup in the properties' constructors; this class only warns
 * about combinations that are safe but surprising.
 *
 * <ul>
 *   <li>{@code saiman.orchestrator.runs.llm-budget-usd-micros} above {@code
 *       saiman.router.max-scope-budget-usd-micros}: the router clamps every scope budget to its
 *       maximum, so a run gets less LLM budget than configured here (spend stays bounded).
 *   <li>{@code saiman.orchestrator.spend.max-paid-calls-per-hour} above {@code
 *       saiman.orchestrator.seller.max-paid-calls-per-hour-at-seller}: the seller answers the calls
 *       over its limit with 429, after the retry was signed, so each of them is held (spend stays
 *       bounded, but held reservations eat the budgets until reconciled).
 * </ul>
 */
@Component
class LimitsConsistencyCheck {

    private static final Logger LOG = LoggerFactory.getLogger(LimitsConsistencyCheck.class);

    LimitsConsistencyCheck(
            RunLimitsProperties runs,
            SpendProperties spend,
            SellerProperties seller,
            ObjectProvider<RouterProperties> router) {
        RouterProperties routerProperties = router.getIfAvailable();
        if (routerProperties != null) {
            warn(llmBudgetWarning(runs.llmBudgetUsdMicros(), routerProperties.maxScopeBudgetUsdMicros()));
            String impossible = llmBudgetAboveDailyCap(runs.llmBudgetUsdMicros(), routerProperties.dailyCapUsdMicros());
            if (impossible != null) {
                throw new IllegalStateException(impossible);
            }
        }
        warn(hourlyPaidCallsWarning(spend.maxPaidCallsPerHour(), seller.maxPaidCallsPerHourAtSeller()));
    }

    private static void warn(@Nullable String warning) {
        if (warning != null) {
            LOG.warn(warning);
        }
    }

    /**
     * The startup error for a run LLM budget above the router's daily cap, or null if it fits: no run could ever be
     * admitted (the cap pre-check needs one run's budget left), so every run would be answered with the replay offer.
     */
    static @Nullable String llmBudgetAboveDailyCap(long runLlmBudgetUsdMicros, long routerDailyCapUsdMicros) {
        if (runLlmBudgetUsdMicros <= routerDailyCapUsdMicros) {
            return null;
        }
        return "saiman.orchestrator.runs.llm-budget-usd-micros (" + runLlmBudgetUsdMicros
                + ") is above saiman.router.daily-cap-usd-micros (" + routerDailyCapUsdMicros
                + "): no run could ever start";
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

    /** The warning for an hourly paid-call limit above the seller's, or null if it fits. */
    static @Nullable String hourlyPaidCallsWarning(int maxPaidCallsPerHour, int sellerMaxPaidCallsPerHour) {
        if (maxPaidCallsPerHour <= sellerMaxPaidCallsPerHour) {
            return null;
        }
        return "saiman.orchestrator.spend.max-paid-calls-per-hour (" + maxPaidCallsPerHour
                + ") is above saiman.orchestrator.seller.max-paid-calls-per-hour-at-seller ("
                + sellerMaxPaidCallsPerHour
                + "): the seller answers the extra paid calls with 429 after they were signed, and each is held";
    }
}
