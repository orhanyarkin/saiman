package io.github.orhanyarkin.x402.server;

import io.github.orhanyarkin.x402.core.PaymentRequirements;
import java.time.Instant;
import java.util.UUID;

/**
 * Published (via {@link org.springframework.context.ApplicationEventPublisher}) when a request to a
 * {@link RequiresPayment} handler in the {@link io.github.orhanyarkin.x402.core.PaymentFlow#UPFRONT
 * upfront} flow was paid -- {@code /settle} succeeded before the handler ran, and an {@link
 * X402PaymentSettledEvent} was already published -- but not served: the handler answered 3xx, 4xx or
 * 5xx, threw, or started unsupported asynchronous processing (ADR-0021).
 *
 * <p>The buyer received {@link #httpStatus()} together with {@code PAYMENT-RESPONSE} (success, the
 * transaction hash), so it knows the money moved. A seller has no key to refund on chain; the
 * intended reaction is to record a credit for the full amount. Published at most once per request,
 * synchronously on the request thread, after the response status is final. {@code (from, nonce)} is
 * the natural dedupe key, as for the other payment events.
 *
 * @param eventId a fresh id for this event, independent of {@code (from, nonce)}
 * @param resourceUrl the request path that was paid for
 * @param requirements the payment requirements that were satisfied
 * @param from the payer's wallet address (the recovered EIP-712 signer)
 * @param nonce the EIP-3009 authorization nonce, {@code 0x} + 64 hex characters
 * @param value the settled amount, as a decimal string of atomic units
 * @param validBefore the authorization's expiry, unix seconds as a decimal string
 * @param payer the payer's wallet address (normally equal to {@link #from()})
 * @param transactionHash the settlement transaction hash, {@code 0x} + 64 hex characters
 * @param httpStatus the status the buyer received, 300-599 ({@code 500} when the handler threw)
 * @param reasonCode a bounded code: {@code handler_redirect} (3xx), {@code handler_client_error}
 *     (4xx), {@code handler_server_error} (5xx), {@code handler_exception} (thrown) or {@code
 *     async_not_supported}
 * @param failedAt when the failure was observed
 */
public record X402PaidRequestFailedEvent(
        UUID eventId,
        String resourceUrl,
        PaymentRequirements requirements,
        String from,
        String nonce,
        String value,
        String validBefore,
        String payer,
        String transactionHash,
        int httpStatus,
        String reasonCode,
        Instant failedAt) {

    /** 3xx answered by the handler. */
    public static final String HANDLER_REDIRECT = "handler_redirect";

    /** 4xx answered by the handler (or by Spring MVC on its behalf, e.g. a validation error). */
    public static final String HANDLER_CLIENT_ERROR = "handler_client_error";

    /** 5xx answered by the handler (typically an {@code @ExceptionHandler} mapping). */
    public static final String HANDLER_SERVER_ERROR = "handler_server_error";

    /** The handler threw an exception no exception handler mapped; the buyer got a 500. */
    public static final String HANDLER_EXCEPTION = "handler_exception";

    /** The handler started asynchronous processing, which this starter does not support. */
    public static final String ASYNC_NOT_SUPPORTED = "async_not_supported";

    /** The reason code for a handler status: one of the three {@code handler_*} status classes. */
    static String reasonForStatus(int status) {
        if (status >= 300 && status < 400) {
            return HANDLER_REDIRECT;
        }
        if (status >= 400 && status < 500) {
            return HANDLER_CLIENT_ERROR;
        }
        return HANDLER_SERVER_ERROR;
    }
}
