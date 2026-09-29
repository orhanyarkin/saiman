package io.github.orhanyarkin.x402.facilitator;

import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequirements;

/**
 * The JSON request body shape shared by {@code POST /verify} and {@code POST /settle}
 * (x402-foundation/x402 {@code specs/x402-specification-v2.md}, facilitator HTTP API).
 *
 * <p>Package-private: an internal wire-glue type, not part of this starter's public API.
 */
record FacilitatorPaymentRequest(
        int x402Version, PaymentPayload paymentPayload, PaymentRequirements paymentRequirements) {}
