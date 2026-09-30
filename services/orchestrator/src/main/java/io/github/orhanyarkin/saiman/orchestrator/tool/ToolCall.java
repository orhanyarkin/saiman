package io.github.orhanyarkin.saiman.orchestrator.tool;

import io.github.orhanyarkin.saiman.orchestrator.payment.PaidCallException;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaidResponse;
import java.util.UUID;

/**
 * A prepared paid call: its payment intent exists, nothing has been sent yet. {@link #send} may be
 * called again with the same intent after a human approved the payment; the transport decides how
 * (HTTP today, MCP later).
 */
public interface ToolCall {

    UUID paymentIntentId();

    /**
     * Sends the request, paying if the spend-control plane allows it.
     *
     * @throws PaidCallException for every outcome that is not a usable response
     */
    PaidResponse send();

    /** The call will not be sent (again): closes a still-PENDING intent. Idempotent. */
    void abandon();
}
