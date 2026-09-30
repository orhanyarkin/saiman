package io.github.orhanyarkin.saiman.sellerapi.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code seller.llm.*}: the limits around model runs, which happen <em>before</em> the x402
 * payment is settled (so a run that ends non-2xx is a run nobody paid for).
 *
 * @param maxInFlightPerPayer model runs one payer may have running at the same time
 * @param maxRunsPerPayerPerHour model runs one payer may start in any rolling hour
 * @param maxUnsettledPerDay model runs per UTC day that started but whose payment has not settled
 *     (all payers together); a settled payment gives its slot back
 * @param deadline end-to-end time budget of one request that runs the model; the model is not
 *     called past it and a late answer is never returned. Cut short per request when the payer's
 *     authorization expires sooner ({@link RequestDeadlines}); {@code deadline +
 *     x402.server.facilitator.connect-timeout + read-timeout + 5 s} must fit in {@link
 *     #MIN_AUTHORIZATION_WINDOW_SECONDS} (checked at startup)
 * @param singleFlightWait how long a request that lost the summary-generation race waits for the
 *     winner's result
 * @param negativeCacheTtl how long a failed summary generation is remembered, so it is not retried
 *     (and paid for at the provider) on every request
 */
@ConfigurationProperties("seller.llm")
public record LlmRunProperties(
        @DefaultValue("2") int maxInFlightPerPayer,
        @DefaultValue("30") int maxRunsPerPayerPerHour,
        @DefaultValue("100") int maxUnsettledPerDay,
        @DefaultValue("25s") Duration deadline,
        @DefaultValue("5s") Duration singleFlightWait,
        @DefaultValue("120s") Duration negativeCacheTtl) {

    /**
     * The smallest authorization window ({@code validBefore - now}) the LLM endpoints accept, so the
     * authorization can not expire while the handler runs: {@code deadline} (25 s) plus the settle
     * call (shipped: facilitator connect 3 s + read 12 s) and a 5 s margin, inside the 60 s the endpoints
     * offer.
     */
    public static final int MIN_AUTHORIZATION_WINDOW_SECONDS = 45;

    public LlmRunProperties {
        if (maxInFlightPerPayer < 1 || maxRunsPerPayerPerHour < 1 || maxUnsettledPerDay < 1) {
            throw new IllegalArgumentException("seller.llm limits must be positive");
        }
        if (deadline.isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("seller.llm.deadline must be positive");
        }
    }
}
