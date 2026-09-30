package io.github.orhanyarkin.saiman.orchestrator.payment;

import org.jspecify.annotations.Nullable;

/**
 * Sends a paid request to the configured seller (ADR-0014 standing rule 1: the agent code never
 * names a transport). No method takes a URL: the target is the {@link PaymentIntentHandle}'s
 * resource, built by {@link PaymentIntentService} from configuration and a {@link SellerEndpoint}.
 *
 * <p>Calls for one run must be sequential (the seller allows two in-flight requests per payer and
 * the orchestrator is one wallet); the client itself does not retry.
 */
public interface PaidResourceClient {

    /**
     * Sends the intent's request, paying the seller's 402 if the spend-control plane allows it.
     *
     * @param jsonBody the request body for a POST endpoint, serialised as JSON; null for GET
     * @return the seller's 2xx response
     * @throws PaymentDeniedException refused before signing (the intent records the reason)
     * @throws PaymentApprovalRequiredException above the approval threshold; nothing was signed
     * @throws PaymentOutcomeUnknownException signed and sent, outcome unknown; the reservation is held
     * @throws SellerCallFailedException the call failed with no unresolved payment
     */
    PaidResponse send(PaymentIntentHandle intent, @Nullable Object jsonBody);
}
