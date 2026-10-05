package io.github.orhanyarkin.saiman.orchestrator.budget;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code GET /api/v1/spend}: the configured limits and one UTC day of spend.
 *
 * @param day the UTC day (ISO-8601 date)
 * @param dailyCap the global cap on reserved plus committed spend per day
 * @param dayReserved reserved (including held) spend counted on the day
 * @param dayCommitted settled spend counted on the day
 * @param byTool the day's payment intents by tool and status
 * @param llmDay today's (UTC) global model-cost cap, whatever {@code day} was asked for (ADR-0026)
 */
public record SpendOverview(
        LocalDate day,
        Money dailyCap,
        Money dayReserved,
        Money dayCommitted,
        Limits limits,
        List<ByTool> byTool,
        LlmDay llmDay) {

    /**
     * The global daily LLM cap (ADR-0011, ADR-0026) as plain integers: USD micro-dollars spent or reserved today
     * (UTC) and the cap. When {@code spentUsdMicros >= capUsdMicros} new public runs are answered with the replay
     * offer.
     */
    @Schema(name = "SpendLlmDay")
    public record LlmDay(long spentUsdMicros, long capUsdMicros) {}

    /**
     * The spend limits as configured (read-only: no endpoint changes them).
     *
     * @param perRequestMax the starter's largest single payment, if configured
     */
    @Schema(name = "SpendLimits")
    public record Limits(
            Money defaultRunBudget,
            Money maxRunBudget,
            Money approvalThreshold,
            @Nullable Money perRequestMax) {}

    /**
     * Intents of one tool in one status. {@code amount} sums the offered amounts (intents that never
     * reached the spend guard have none and add 0).
     */
    @Schema(name = "SpendByTool")
    public record ByTool(String tool, String status, long count, Money amount) {}
}
