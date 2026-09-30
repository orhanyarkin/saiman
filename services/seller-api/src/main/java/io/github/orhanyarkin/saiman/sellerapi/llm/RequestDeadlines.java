package io.github.orhanyarkin.saiman.sellerapi.llm;

import io.github.orhanyarkin.x402.server.X402PaymentContext;
import io.github.orhanyarkin.x402.server.X402ServerProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The time budget of one paid request whose handler works before settlement (ADR-0015).
 *
 * <p>The budget is {@code min(seller.llm.deadline, validBefore - now - settleMargin)}: the
 * configured deadline, cut short when the payer's authorization expires sooner than that. The
 * settle margin is the facilitator connect timeout plus its read timeout plus {@value
 * #EXTRA_MARGIN_SECONDS} s, i.e. the time the {@code /settle} call after the handler may need; a handler finishing inside the budget
 * therefore leaves the authorization settleable. Without a verified payment (not a paid request)
 * the configured deadline applies alone.
 *
 * <p>At startup the configured timeouts must fit inside the smallest window the LLM endpoints
 * accept: {@code deadline + facilitator connect timeout + read timeout + 5 s <= }{@link
 * LlmRunProperties#MIN_AUTHORIZATION_WINDOW_SECONDS}. That keeps the configurable values honest
 * against the constant window; the per-request {@code validBefore} cut covers the time already
 * spent before the handler (payment verification) and clock rounding.
 */
@Component
public class RequestDeadlines {

    /** Extra seconds on top of the facilitator timeouts, as in the starter's own window check. */
    public static final int EXTRA_MARGIN_SECONDS = 5;

    private final Duration configured;
    private final Duration settleMargin;
    private final Clock clock;

    RequestDeadlines(LlmRunProperties llm, X402ServerProperties x402, Clock clock) {
        Duration connectTimeout = x402.facilitator().connectTimeout();
        Duration readTimeout = x402.facilitator().readTimeout();
        requireFitsWindow(
                llm.deadline(), connectTimeout, readTimeout, LlmRunProperties.MIN_AUTHORIZATION_WINDOW_SECONDS);
        this.configured = llm.deadline();
        this.settleMargin = connectTimeout.plus(readTimeout).plusSeconds(EXTRA_MARGIN_SECONDS);
        this.clock = clock;
    }

    /** The deadline for {@code request}, starting now. */
    public Deadline forRequest(HttpServletRequest request) {
        return Deadline.after(
                budget(configured, settleMargin, X402PaymentContext.validBefore(request), clock.instant()));
    }

    /**
     * {@code min(configured, validBefore - now - settleMargin)}, never negative; {@code configured}
     * alone when there is no {@code validBefore}.
     */
    static Duration budget(Duration configured, Duration settleMargin, @Nullable Instant validBefore, Instant now) {
        if (validBefore == null) {
            return configured;
        }
        Duration byAuthorization = Duration.between(now, validBefore).minus(settleMargin);
        if (byAuthorization.isNegative()) {
            return Duration.ZERO;
        }
        return byAuthorization.compareTo(configured) < 0 ? byAuthorization : configured;
    }

    /**
     * @throws IllegalStateException if {@code deadline + connectTimeout + readTimeout + 5 s} exceeds {@code
     *     minWindowSeconds}: a handler could then still be running, or settling, when the smallest
     *     accepted authorization expires
     */
    static void requireFitsWindow(
            Duration deadline, Duration connectTimeout, Duration readTimeout, int minWindowSeconds) {
        Duration needed = deadline.plus(connectTimeout).plus(readTimeout).plusSeconds(EXTRA_MARGIN_SECONDS);
        if (needed.compareTo(Duration.ofSeconds(minWindowSeconds)) > 0) {
            throw new IllegalStateException(
                    "seller.llm.deadline + x402.server.facilitator.connect-timeout + read-timeout + "
                            + EXTRA_MARGIN_SECONDS + " s must not exceed the " + minWindowSeconds
                            + " s minimum authorization window of the LLM endpoints");
        }
    }
}
