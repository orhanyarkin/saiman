package io.github.orhanyarkin.x402.facilitator;

import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.VerifyResponse;

/**
 * The seller-side view of an x402 facilitator: verifies a signed payment authorization and settles
 * it on chain.
 *
 * <p>The default implementation is {@link HttpFacilitatorClient}, wired by {@code
 * X402ServerAutoConfiguration} against {@code x402.server.facilitator.url}
 * (default the public {@code https://x402.org/facilitator}); register a bean of this type ({@code
 * @ConditionalOnMissingBean}) to replace it, e.g. with a facilitator you run yourself. Tests use
 * {@code FakeFacilitator} (test fixture, in-process HTTP server).
 */
public interface FacilitatorClient {

    /**
     * Verifies a signed payment authorization without settling it: checks the signature, the
     * authorization window and (facilitator-side) balance/allowance, without broadcasting a
     * transaction.
     *
     * @throws FacilitatorException if the call fails (network error, timeout, open circuit
     *     breaker, non-2xx or oversized response, undecodable response)
     */
    VerifyResponse verify(PaymentPayload payload, PaymentRequirements requirements);

    /**
     * Settles a previously verified payment authorization: broadcasts {@code
     * transferWithAuthorization} on chain. Never retried by {@link HttpFacilitatorClient} (a retry
     * could double-settle an authorization whose first attempt actually succeeded but whose
     * response was lost).
     *
     * @throws FacilitatorException if the call fails; the settlement outcome is then ambiguous, so
     *     callers must not treat this the same as a well-formed {@link SettlementResponse} with
     *     {@code success == false}
     */
    SettlementResponse settle(PaymentPayload payload, PaymentRequirements requirements);

    /**
     * The (version, scheme, network) combinations this facilitator supports.
     *
     * @throws FacilitatorException if the call fails
     */
    SupportedResponse supported();
}
