package io.github.orhanyarkin.x402.server;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring MVC handler method as requiring an x402 payment before it runs.
 *
 * <p>Enforced by {@link RequiresPaymentInterceptor} and {@link X402SettlementFilter}: an incoming
 * request without a valid, settled payment never reaches the annotated method. See {@code
 * docs/design/m1-x402.md} ("Server flow") for the full decode/verify/settle sequence.
 *
 * <p>This starter only ever offers the {@code exact} scheme on {@link
 * io.github.orhanyarkin.x402.core.TestnetAssets#NETWORK} in test USDC (ADR-0008): there is no
 * network or asset attribute here.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPayment {

    /**
     * The price, as a decimal string of atomic USDC units (6 decimals; e.g. {@code "10000"} is
     * 0.01 USDC).
     *
     * <p>Resolved with the Spring embedded value resolver at startup, so a property placeholder
     * such as {@code "${seller.prices.disclosure-summary}"} or a literal atomic-unit string both
     * work. Parsed with {@link io.github.orhanyarkin.x402.core.AssetAmount#parse(String)}; a
     * missing, non-numeric or zero price fails application startup (fail closed, no {@code
     * enabled} flag) rather than silently serving the resource for free.
     */
    String price();

    /** Human-readable description of the resource, echoed in the {@code PAYMENT-REQUIRED} body. */
    String description() default "";

    /**
     * The minimum remaining validity ({@code validBefore - now}), in seconds, an authorization must
     * have for this handler; a shorter window is rejected with {@code 402} ({@code
     * window_too_short}) before the facilitator is called.
     *
     * <p>{@code 0} (the default) means the starter-wide minimum only (facilitator read timeout + 5
     * s, so {@code /settle} cannot lose a race against the authorization's expiry). A handler whose
     * work is slow or costly (e.g. an LLM call that runs <em>before</em> settlement) should ask for
     * more than its worst-case runtime, so the authorization cannot expire while the handler runs.
     * The effective minimum is the larger of the two values; a value above the upper bound the
     * server accepts ({@code x402.server.max-timeout-seconds} + clock skew) fails startup.
     */
    int minWindowSeconds() default 0;
}
