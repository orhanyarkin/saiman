package io.github.orhanyarkin.x402.client;

import io.github.orhanyarkin.x402.core.AssetAmount;
import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.ExactEvmPayload;
import io.github.orhanyarkin.x402.core.PaymentFlow;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequired;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.UnsupportedPaymentException;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402CodecException;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.evm.Eip3009TypedData;
import io.github.orhanyarkin.x402.evm.PaymentSigner;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

/**
 * A {@link ClientHttpRequestInterceptor} that pays for a 402 response and retries, once.
 *
 * <p><b>Opt-in.</b> This starter never attaches this interceptor to every {@code RestClient}
 * automatically (payments have real financial consequences even on testnet); an application adds
 * an instance to the specific {@code RestClient.Builder}s it wants to pay through, e.g. {@code
 * builder.requestInterceptor(x402PaymentInterceptor)}.
 *
 * <p><b>Every request sent through such a builder</b> — not only ones that turn out to need
 * payment — must carry a non-blank {@value #IDEMPOTENCY_KEY_HEADER} header, checked before any
 * network call; see {@link #IDEMPOTENCY_KEY_HEADER}. A request that already carries a {@code
 * PAYMENT-SIGNATURE} header (e.g. a caller replaying a previously captured payload) is passed
 * through unchanged instead: this interceptor never signs a second payment for a request that
 * already has one attached.
 *
 * <p><b>Flow.</b> The request is sent as-is; a non-402 response is returned unchanged. On 402, the
 * {@code PAYMENT-REQUIRED} header is decoded and the first offer that (a) passes {@link
 * TestnetAssets#requireSupported(PaymentRequirements)}, (b) has a {@code payTo} in the configured
 * allowlist, (c) an amount at or below the configured maximum and (d) a positive {@code
 * maxTimeoutSeconds} is selected — with no such offer, {@link PaymentRejectedException} is thrown
 * and nothing is signed. This interceptor also refuses to pay at all over a non-{@code https} URL
 * that isn't loopback ({@code localhost}/{@code 127.0.0.1}/{@code ::1}) or a host named exactly in
 * {@code x402.client.allowed-plaintext-hosts} (see {@link PlaintextHostAllowlist}), before any
 * signing: a plaintext {@code PAYMENT-SIGNATURE} header is a bearer instrument for its
 * authorization until {@code validBefore}. {@link SpendGuard#reserve(PaymentIntent)} runs next,
 * <em>before</em> any
 * signing (rule 3 in {@code CLAUDE.md}); a denial means {@link
 * PaymentSigner#signTransferWithAuthorization} is never called. Only once a reservation is granted
 * is an EIP-3009 authorization built (payer = the signer's address, {@code validAfter} {@value
 * #CLOCK_SKEW_SECONDS} seconds in the past — generous enough to tolerate real clock drift, since
 * EIP-3009 requires {@code block.timestamp > validAfter} — {@code validBefore} at most {@value
 * #MAX_VALIDITY_SECONDS} seconds ahead, a fresh random nonce), signed and retried with a {@code
 * PAYMENT-SIGNATURE} header — never more than once. If signing or encoding itself fails, nothing
 * has been sent yet, so the reservation is released and the failure rethrown. After signing and
 * before the paid retry is sent, {@link SpendGuard#signed(SpendReservation, Eip3009Authorization)}
 * is called; if it throws, the signature is dropped unsent, the reservation is released and the
 * failure rethrown (fail closed).
 *
 * <p><b>Outcome bookkeeping.</b> A 2xx response carrying a successful {@code PAYMENT-RESPONSE}
 * header with a well-formed {@code transaction} hash commits the reservation. Anything else on the
 * paid retry — a second 402, a 5xx, a 2xx without a decodable, successful, well-formed-transaction
 * settlement, or an {@link IOException} — is treated as an outcome this process cannot safely
 * resolve on its own: the signed authorization already left the process, so it stays settleable by
 * the server (or a facilitator behind it) until {@code validBefore} regardless of what this
 * response says. The reservation is therefore left reserved in every one of these cases — never
 * released, never committed — so the same idempotency key can never be reused for a second
 * signature (fail closed, ADR-0008); see {@link SpendGuard#release}'s Javadoc. A second 402 on the
 * paid retry throws {@link PaymentDeclinedAfterSigningException} (a distinct, more specific
 * subtype), so a caller can never mistake it for the plain, safe-to-retry-under-a-fresh-key
 * rejection {@link PaymentRejectedException} represents; every other non-2xx/non-402 outcome
 * throws the more general {@link AmbiguousPaymentException}.
 *
 * <p><b>Redirects.</b> This interceptor does not itself follow or block redirects — that is the
 * underlying {@code ClientHttpRequestFactory}'s job. Spring Boot 4.1 follows redirects by default,
 * which would forward the {@code PAYMENT-SIGNATURE} and {@code Idempotency-Key} headers on the
 * paid retry to whatever host a 3xx response names. Any application wiring this interceptor onto a
 * {@code RestClient} <b>must</b> configure that {@code RestClient}'s request factory to not follow
 * redirects, e.g. the property {@code spring.http.clients.redirects=dont-follow}, or {@link
 * X402RestClients#nonRedirectingRequestFactory()}.
 *
 * <p><b>Observability.</b> Records a Micrometer {@link Observation} named {@value
 * #OBSERVATION_NAME} with only the allowlisted key-values from the ADR-0006 amendment: low
 * cardinality {@code x402.network}/{@code x402.scheme}/{@code x402.asset}/{@code x402.outcome}
 * (defaulted to {@code "unknown"} at the start of every payment attempt, so the tag-key set is
 * constant regardless of outcome; {@code x402.outcome} is one of {@code denied}, {@code rejected},
 * {@code declined_after_signing}, {@code ambiguous} or {@code settled}), high cardinality {@code
 * x402.payer}/{@code x402.tx_hash} (the latter recorded only if it matches a well-formed
 * transaction hash, else {@code "invalid"}). No header value, signature or payload content is ever
 * added. This class registers no metrics itself — {@code X402ObservationAutoConfiguration} owns
 * the counters/summaries derived from this observation.
 */
public final class X402PaymentInterceptor implements ClientHttpRequestInterceptor {

    /** Required request header carrying the caller's idempotency key. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Name of the {@link Observation} this interceptor records for every payment attempt. */
    public static final String OBSERVATION_NAME = "x402.client.payment";

    private static final long CLOCK_SKEW_SECONDS = 600;
    private static final long MAX_VALIDITY_SECONDS = 60;
    private static final String UNKNOWN_TAG = "unknown";
    private static final Pattern TX_HASH_PATTERN = Pattern.compile("0x[0-9a-fA-F]{64}");

    private final PaymentSigner signer;
    private final SpendGuard spendGuard;
    private final X402Codec codec;
    private final long maxAmountPerRequest;
    private final List<String> allowedPayTo;
    private final List<String> allowedPlaintextHosts;
    private final ObservationRegistry observationRegistry;
    private final Clock clock;

    /** Creates an interceptor that pays only over {@code https} or to a loopback host. */
    public X402PaymentInterceptor(
            PaymentSigner signer,
            SpendGuard spendGuard,
            X402Codec codec,
            long maxAmountPerRequest,
            List<String> allowedPayTo,
            ObservationRegistry observationRegistry) {
        this(signer, spendGuard, codec, maxAmountPerRequest, allowedPayTo, List.of(), observationRegistry);
    }

    /**
     * Creates an interceptor that may also pay over plain {@code http} to the hosts named exactly
     * in {@code allowedPlaintextHosts} (see {@link PlaintextHostAllowlist}).
     *
     * @throws IllegalStateException if {@code allowedPlaintextHosts} holds anything but exact host
     *     names
     */
    public X402PaymentInterceptor(
            PaymentSigner signer,
            SpendGuard spendGuard,
            X402Codec codec,
            long maxAmountPerRequest,
            List<String> allowedPayTo,
            List<String> allowedPlaintextHosts,
            ObservationRegistry observationRegistry) {
        this(
                signer,
                spendGuard,
                codec,
                maxAmountPerRequest,
                allowedPayTo,
                allowedPlaintextHosts,
                observationRegistry,
                Clock.systemUTC());
    }

    /** Package-private: lets tests fix "now" instead of racing the system clock. */
    X402PaymentInterceptor(
            PaymentSigner signer,
            SpendGuard spendGuard,
            X402Codec codec,
            long maxAmountPerRequest,
            List<String> allowedPayTo,
            ObservationRegistry observationRegistry,
            Clock clock) {
        this(signer, spendGuard, codec, maxAmountPerRequest, allowedPayTo, List.of(), observationRegistry, clock);
    }

    /** Package-private: lets tests fix "now" instead of racing the system clock. */
    X402PaymentInterceptor(
            PaymentSigner signer,
            SpendGuard spendGuard,
            X402Codec codec,
            long maxAmountPerRequest,
            List<String> allowedPayTo,
            List<String> allowedPlaintextHosts,
            ObservationRegistry observationRegistry,
            Clock clock) {
        this.signer = Objects.requireNonNull(signer, "signer must not be null");
        this.spendGuard = Objects.requireNonNull(spendGuard, "spendGuard must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        if (maxAmountPerRequest <= 0) {
            throw new IllegalArgumentException("maxAmountPerRequest must be a positive atomic amount");
        }
        this.maxAmountPerRequest = maxAmountPerRequest;
        this.allowedPayTo = PayToAllowlist.requireValidAndNormalize(allowedPayTo);
        // The interceptor only ever pays on TestnetAssets.NETWORK (see isAcceptable), so that is
        // the network the plaintext exception is checked against.
        this.allowedPlaintextHosts =
                PlaintextHostAllowlist.requireValidAndNormalize(allowedPlaintextHosts, TestnetAssets.NETWORK);
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (request.getHeaders().containsHeader(X402Headers.PAYMENT_SIGNATURE)) {
            // The caller already attached a payment (e.g. replaying a captured payload): never
            // sign a second one for the same request.
            return execution.execute(request, body);
        }
        String idempotencyKey = requireIdempotencyKey(request);

        ClientHttpResponse initialResponse = execution.execute(request, body);
        if (initialResponse.getStatusCode().value() != 402) {
            return initialResponse;
        }

        Observation observation = Observation.createNotStarted(OBSERVATION_NAME, observationRegistry);
        observation
                .lowCardinalityKeyValue("x402.network", UNKNOWN_TAG)
                .lowCardinalityKeyValue("x402.scheme", UNKNOWN_TAG)
                .lowCardinalityKeyValue("x402.asset", UNKNOWN_TAG)
                .lowCardinalityKeyValue("x402.outcome", UNKNOWN_TAG);
        observation.start();
        try {
            PaymentRequired paymentRequired = decodePaymentRequired(initialResponse);
            PaymentRequirements chosen = selectOffer(paymentRequired.accepts());
            observation.lowCardinalityKeyValue("x402.network", chosen.network());
            observation.lowCardinalityKeyValue("x402.scheme", chosen.scheme());
            // The constant, not chosen.asset(): requireSupported only compares case-insensitively,
            // so the wire value's casing is the (untrusted) server's choice, not ours to echo.
            observation.lowCardinalityKeyValue("x402.asset", TestnetAssets.USDC_ADDRESS);
            return pay(request, body, execution, idempotencyKey, chosen, observation);
        } catch (SpendDeniedException e) {
            observation.lowCardinalityKeyValue("x402.outcome", "denied");
            observation.error(e);
            throw e;
        } catch (PaymentRejectedException e) {
            observation.lowCardinalityKeyValue("x402.outcome", "rejected");
            observation.error(e);
            throw e;
        } catch (PaymentDeclinedAfterSigningException e) {
            observation.lowCardinalityKeyValue("x402.outcome", "declined_after_signing");
            observation.error(e);
            throw e;
        } catch (AmbiguousPaymentException e) {
            observation.lowCardinalityKeyValue("x402.outcome", "ambiguous");
            observation.error(e);
            throw e;
        } catch (IOException e) {
            observation.lowCardinalityKeyValue("x402.outcome", "ambiguous");
            observation.error(e);
            throw e;
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    private ClientHttpResponse pay(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution,
            String idempotencyKey,
            PaymentRequirements chosen,
            Observation observation)
            throws IOException {
        requireSecureOrLoopback(request.getURI());

        PaymentIntent intent = new PaymentIntent(idempotencyKey, request.getURI(), chosen);
        // Runs before any signing: a denial here never touches the signer (rule 3 in CLAUDE.md).
        SpendReservation reservation = spendGuard.reserve(intent);

        String encodedPayload;
        Eip3009Authorization authorization;
        try {
            authorization = buildAuthorization(chosen);
            String signature = signer.signTransferWithAuthorization(authorization);
            PaymentPayload paymentPayload =
                    new PaymentPayload(2, null, chosen, new ExactEvmPayload(signature, authorization), null);
            encodedPayload = codec.encodePaymentPayload(paymentPayload);
        } catch (RuntimeException e) {
            // Nothing has been sent yet: safe to release (see SpendGuard#release's Javadoc).
            releaseQuietly(reservation, "signing failed", e);
            throw e;
        }
        try {
            // Last point before the signature can leave this process: the guard records (or
            // vetoes) the signed authorization first.
            spendGuard.signed(reservation, authorization);
        } catch (RuntimeException e) {
            // The signature exists only in this process's memory and is dropped here, never sent.
            releaseQuietly(reservation, "signed hook failed", e);
            throw e;
        }

        ClientHttpResponse retryResponse = execution.execute(new PaymentRequestWrapper(request, encodedPayload), body);

        int status = retryResponse.getStatusCode().value();
        if (status >= 200 && status < 300) {
            SettlementResponse settlement = tryDecodeSettlement(retryResponse);
            if (settlement != null && settlement.success() && isWellFormedTxHash(settlement.transaction())) {
                spendGuard.commit(reservation, settlement);
                observation.lowCardinalityKeyValue("x402.outcome", "settled");
                observation.highCardinalityKeyValue("x402.payer", signer.address());
                observation.highCardinalityKeyValue("x402.tx_hash", sanitizeTxHash(settlement.transaction()));
                return retryResponse;
            }
            retryResponse.close();
            // The signature already left the process: leave the reservation held (see
            // SpendGuard#release's Javadoc) rather than releasing it. Covers a missing/undecodable
            // PAYMENT-RESPONSE, success=false, and success=true with a missing or malformed
            // transaction (the last of which would otherwise NPE inside a null-unsafe hash check
            // and, worse, commit a reservation this starter can never point at an actual transaction).
            throw new AmbiguousPaymentException("server answered 2xx to a payment-carrying request without a successful"
                    + " PAYMENT-RESPONSE header carrying a well-formed transaction hash; payment outcome is"
                    + " ambiguous");
        }
        if (status == 402) {
            // Do NOT release: the signed authorization already left the process and stays
            // settleable (by this server or, on a hostile one, by whoever else it was handed to)
            // until validBefore, regardless of this response. Keep the reservation held so the
            // same idempotency key can never be used to produce a second signature. Throwing here
            // (rather than returning the 402 response) makes this outcome impossible to mistake
            // for a plain, safe-to-retry-under-a-fresh-key rejection.
            retryResponse.close();
            throw new PaymentDeclinedAfterSigningException(status);
        }
        retryResponse.close();
        throw new AmbiguousPaymentException(
                "payment-carrying request failed with HTTP " + status + "; payment outcome is ambiguous");
    }

    private Eip3009Authorization buildAuthorization(PaymentRequirements chosen) {
        Instant now = Instant.now(clock);
        long validAfter = Math.max(now.getEpochSecond() - CLOCK_SKEW_SECONDS, 0);
        long validitySeconds = Math.min(chosen.maxTimeoutSeconds(), MAX_VALIDITY_SECONDS);
        long validBefore = now.getEpochSecond() + Math.max(validitySeconds, 0);
        return new Eip3009Authorization(
                signer.address(),
                chosen.payTo(),
                chosen.amount(),
                Long.toString(validAfter),
                Long.toString(validBefore),
                Eip3009TypedData.randomNonce());
    }

    /**
     * The first acceptable offer, preferring the {@code authorization} flow (spec section 6.1: a
     * client SHOULD prefer it, since nothing is settled unless the resource is served): an {@code
     * upfront} offer is chosen only when no acceptable {@code authorization} offer exists. The commit
     * rule does not depend on the flow: a non-2xx answer after paying is ambiguous either way.
     */
    private PaymentRequirements selectOffer(List<PaymentRequirements> accepts) {
        PaymentRequirements upfront = null;
        for (PaymentRequirements offer : accepts) {
            if (isAcceptable(offer)) {
                if (PaymentFlow.of(offer) == PaymentFlow.AUTHORIZATION) {
                    return offer;
                }
                if (upfront == null) {
                    upfront = offer;
                }
            }
        }
        if (upfront != null) {
            return upfront;
        }
        throw new PaymentRejectedException(
                "none of the server's payment offers is one this starter is configured to pay: check"
                        + " network/asset support, x402.client.allowed-pay-to and"
                        + " x402.client.max-amount-per-request");
    }

    private boolean isAcceptable(PaymentRequirements offer) {
        try {
            TestnetAssets.requireSupported(offer);
        } catch (UnsupportedPaymentException e) {
            return false;
        }
        if (offer.maxTimeoutSeconds() <= 0) {
            return false;
        }
        boolean payToAllowed = allowedPayTo.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(offer.payTo()));
        if (!payToAllowed) {
            return false;
        }
        long amount;
        try {
            amount = AssetAmount.parse(offer.amount()).atomicUnits();
        } catch (IllegalArgumentException e) {
            return false;
        }
        return amount <= maxAmountPerRequest;
    }

    private PaymentRequired decodePaymentRequired(ClientHttpResponse initialResponse) throws IOException {
        try (initialResponse) {
            String headerValue = initialResponse.getHeaders().getFirst(X402Headers.PAYMENT_REQUIRED);
            if (headerValue == null) {
                throw new PaymentRejectedException(
                        "server answered 402 without a " + X402Headers.PAYMENT_REQUIRED + " header");
            }
            try {
                return codec.decodePaymentRequired(headerValue);
            } catch (X402CodecException e) {
                throw new PaymentRejectedException(
                        "server's " + X402Headers.PAYMENT_REQUIRED + " header could not be decoded");
            }
        }
    }

    private @Nullable SettlementResponse tryDecodeSettlement(ClientHttpResponse retryResponse) {
        String headerValue;
        try {
            headerValue = retryResponse.getHeaders().getFirst(X402Headers.PAYMENT_RESPONSE);
        } catch (RuntimeException e) {
            return null;
        }
        if (headerValue == null) {
            return null;
        }
        try {
            return codec.decodeSettlementResponse(headerValue);
        } catch (X402CodecException e) {
            return null;
        }
    }

    /**
     * @param transaction {@link SettlementResponse#transaction()}; the record component itself is
     *     not {@code @Nullable}, but nothing stops a server from omitting the JSON field, which
     *     {@link X402Codec}'s tolerant decode leaves as {@code null} rather than rejecting
     */
    private static boolean isWellFormedTxHash(@Nullable String transaction) {
        return transaction != null && TX_HASH_PATTERN.matcher(transaction).matches();
    }

    /** Null-safe: see {@link #isWellFormedTxHash}. */
    private static String sanitizeTxHash(@Nullable String transaction) {
        return transaction != null && TX_HASH_PATTERN.matcher(transaction).matches() ? transaction : "invalid";
    }

    private static String requireIdempotencyKey(HttpRequest request) {
        String value = request.getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
        if (value == null || value.isBlank()) {
            throw new PaymentRejectedException("request must carry a non-blank " + IDEMPOTENCY_KEY_HEADER
                    + " header before any x402" + " payment can be attempted");
        }
        return value;
    }

    /**
     * @throws PaymentRejectedException if {@code uri}'s scheme is not {@code https}, and it is not
     *     plain {@code http} to a loopback host ({@code localhost}/{@code 127.0.0.1}/{@code ::1}) or
     *     to a host named exactly in {@code x402.client.allowed-plaintext-hosts}. A {@code
     *     PAYMENT-SIGNATURE} header is a bearer instrument for the authorization it carries until
     *     {@code validBefore}, so it must not travel in the clear or be forwarded by a redirect to
     *     an untrusted host. The host comes from {@link URI#getHost()}, so userinfo ({@code
     *     http://seller-api@evil.com}) never counts as the host.
     */
    private void requireSecureOrLoopback(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            String host = uri.getHost();
            if (host != null
                    && (host.equalsIgnoreCase("localhost")
                            || host.equals("127.0.0.1")
                            || host.equals("::1")
                            || host.equals("[::1]")
                            || PlaintextHostAllowlist.contains(allowedPlaintextHosts, host))) {
                return;
            }
        }
        throw new PaymentRejectedException("refusing to sign a payment for a non-https URL that is neither loopback"
                + " nor an allowed plaintext host");
    }

    /** Releases {@code reservation}; a failing release never hides the original failure. */
    private void releaseQuietly(SpendReservation reservation, String reason, RuntimeException cause) {
        try {
            spendGuard.release(reservation, reason);
        } catch (RuntimeException releaseFailure) {
            cause.addSuppressed(releaseFailure);
        }
    }

    /**
     * Wraps the original request, adding the {@code PAYMENT-SIGNATURE} header for the retry.
     * {@code HttpRequestWrapper} delegates {@link #getHeaders()} to the wrapped request by
     * default, so this fully overrides it with a combined, read-only header set rather than
     * relying on that delegate being mutable. The body ({@code byte[]}, passed unchanged to {@link
     * ClientHttpRequestExecution#execute}) is not touched, so a POST/PUT body is resent verbatim.
     */
    private static final class PaymentRequestWrapper extends HttpRequestWrapper {

        private final HttpHeaders headers;

        PaymentRequestWrapper(HttpRequest request, String paymentSignatureHeaderValue) {
            super(request);
            HttpHeaders combined = new HttpHeaders();
            combined.addAll(request.getHeaders());
            combined.set(X402Headers.PAYMENT_SIGNATURE, paymentSignatureHeaderValue);
            this.headers = HttpHeaders.readOnlyHttpHeaders(combined);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
