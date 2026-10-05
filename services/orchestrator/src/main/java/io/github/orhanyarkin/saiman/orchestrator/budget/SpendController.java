package io.github.orhanyarkin.saiman.orchestrator.budget;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.x402.client.X402ClientProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/spend?day=YYYY-MM-DD}: the configured limits plus one UTC day of spend (default
 * today). Read-only: limits come from {@link SpendProperties} and the starter's client properties,
 * the numbers are aggregated in SQL from {@code spend_day} and {@code payment_intent}.
 *
 * <p>An intent belongs to the day it reserved on ({@code reserved_day}); one that never reserved
 * (denied before the reservation) belongs to the UTC day it was created, so denials stay visible.
 */
@RestController
class SpendController {

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final JdbcClient jdbc;
    private final SpendProperties spend;
    private final @Nullable Long perRequestMax;
    private final Clock clock;

    SpendController(JdbcClient jdbc, SpendProperties spend, X402ClientProperties x402) {
        this.jdbc = jdbc;
        this.spend = spend;
        this.perRequestMax = x402.maxAmountPerRequest();
        this.clock = Clock.systemUTC();
    }

    @GetMapping(path = "/api/v1/spend", produces = MediaType.APPLICATION_JSON_VALUE)
    SpendOverview spend(@RequestParam(required = false) @Nullable String day) {
        LocalDate date = parse(day);
        long[] counters = jdbc.sql("SELECT reserved_atomic, committed_atomic FROM spend_day WHERE day = :day")
                .param("day", date)
                .query((rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .optional()
                .orElse(new long[] {0, 0});
        List<SpendOverview.ByTool> byTool = jdbc.sql("""
                        SELECT tool, status, count(*) AS n, coalesce(sum(amount_atomic), 0) AS amount
                          FROM payment_intent
                         WHERE coalesce(reserved_day, (created_at AT TIME ZONE 'UTC')::date) = :day
                         GROUP BY tool, status
                         ORDER BY tool, status
                        """)
                .param("day", date)
                .query((rs, row) -> new SpendOverview.ByTool(
                        rs.getString("tool"),
                        rs.getString("status"),
                        rs.getLong("n"),
                        Money.usdc(rs.getLong("amount"))))
                .list();
        return new SpendOverview(
                date,
                Money.usdc(spend.dailyCapAtomic()),
                Money.usdc(counters[0]),
                Money.usdc(counters[1]),
                new SpendOverview.Limits(
                        Money.usdc(spend.defaultRunBudgetAtomic()),
                        Money.usdc(spend.maxRunBudgetAtomic()),
                        Money.usdc(spend.approvalThresholdAtomic()),
                        perRequestMax == null ? null : Money.usdc(perRequestMax)),
                byTool);
    }

    private LocalDate parse(@Nullable String day) {
        if (day == null) {
            return LocalDate.now(clock.withZone(ZoneOffset.UTC));
        }
        try {
            if (ISO_DATE.matcher(day).matches()) {
                return LocalDate.parse(day);
            }
        } catch (DateTimeParseException e) {
            // falls through to the fixed message
        }
        throw new ErrorResponseException(
                HttpStatus.BAD_REQUEST,
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "day must be a date as YYYY-MM-DD"),
                null);
    }
}
