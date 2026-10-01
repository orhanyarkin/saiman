package io.github.orhanyarkin.saiman.orchestrator.budget;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.orchestrator.spend.*}: the limits of the spend-control plane (ADR-0013). All amounts
 * are USDC atomic units (6 decimals). Validated in the constructor, so a bad value fails startup with
 * a message that states the rule.
 *
 * <p>These limits are configuration, never request or model input: a run's budget is chosen once at
 * {@code POST /runs} through {@link #resolveRunBudget(Long)} and is immutable afterwards.
 *
 * @param dailyCapAtomic global cap on reserved + committed spend per UTC day, across all runs
 * @param defaultRunBudgetAtomic budget of a run that does not ask for one
 * @param maxRunBudgetAtomic the largest budget a run may ask for
 * @param approvalThresholdAtomic a single payment strictly above this waits for a human decision
 * @param approvalTimeout how long a pending approval waits before it expires
 * @param maxPaidCallsPerRun paid calls (reserved, signed, settled or held) one run may make
 * @param maxToolCallsPerRun tool calls one run may make (counted by the tool gateway)
 * @param maxPaidCallsPerHour paid calls (reserved, signed, settled or held) the orchestrator's one
 *     wallet may make in any rolling hour, across all runs; kept below the seller's own per-payer
 *     hourly limit, so the seller never answers a signed retry with 429
 */
@ConfigurationProperties("saiman.orchestrator.spend")
public record SpendProperties(
        @DefaultValue("1000000") long dailyCapAtomic,
        @DefaultValue("50000") long defaultRunBudgetAtomic,
        @DefaultValue("200000") long maxRunBudgetAtomic,
        @DefaultValue("10000") long approvalThresholdAtomic,
        @DefaultValue("5m") Duration approvalTimeout,
        @DefaultValue("4") int maxPaidCallsPerRun,
        @DefaultValue("6") int maxToolCallsPerRun,
        @DefaultValue("25") int maxPaidCallsPerHour) {

    public SpendProperties {
        requirePositive(dailyCapAtomic, "daily-cap-atomic");
        requirePositive(defaultRunBudgetAtomic, "default-run-budget-atomic");
        requirePositive(maxRunBudgetAtomic, "max-run-budget-atomic");
        requirePositive(approvalThresholdAtomic, "approval-threshold-atomic");
        requirePositive(maxPaidCallsPerRun, "max-paid-calls-per-run");
        requirePositive(maxToolCallsPerRun, "max-tool-calls-per-run");
        requirePositive(maxPaidCallsPerHour, "max-paid-calls-per-hour");
        if (maxRunBudgetAtomic < defaultRunBudgetAtomic) {
            throw new IllegalArgumentException(
                    "saiman.orchestrator.spend.max-run-budget-atomic must be >= default-run-budget-atomic");
        }
        if (defaultRunBudgetAtomic > dailyCapAtomic) {
            throw new IllegalArgumentException(
                    "saiman.orchestrator.spend.default-run-budget-atomic must be <= daily-cap-atomic");
        }
        if (approvalTimeout.isNegative() || approvalTimeout.isZero()) {
            throw new IllegalArgumentException("saiman.orchestrator.spend.approval-timeout must be positive");
        }
    }

    /**
     * The budget for a new run: the default if none was requested, else the requested amount.
     *
     * @throws IllegalArgumentException if the requested budget is not positive or above {@link
     *     #maxRunBudgetAtomic()}
     */
    public long resolveRunBudget(@Nullable Long requestedAtomic) {
        if (requestedAtomic == null) {
            return defaultRunBudgetAtomic;
        }
        if (requestedAtomic <= 0 || requestedAtomic > maxRunBudgetAtomic) {
            throw new IllegalArgumentException("run budget must be between 1 and the configured maximum");
        }
        return requestedAtomic;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("saiman.orchestrator.spend." + name + " must be positive");
        }
    }
}
