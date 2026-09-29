package io.github.orhanyarkin.x402.sample;

import io.github.orhanyarkin.x402.client.X402PaymentInterceptor;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequired;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402CodecException;
import io.github.orhanyarkin.x402.core.X402Headers;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The {@code replay} command: resends the last {@code PAYMENT-SIGNATURE} header {@link BuyCommand}
 * sent, verbatim, with a fresh {@code Idempotency-Key} and <em>no</em> {@code
 * X402PaymentInterceptor}. This is a replay-protection probe, not a payment attempt: the signed
 * EIP-3009 authorization's nonce was already claimed by the first {@code buy}, so the server (or
 * the facilitator behind it) is expected to reject this with a second 402 — exiting 0 only when it
 * does.
 */
@Component
final class ReplayCommand {

    private static final Path LAST_PAYMENT_FILE = Path.of("build", "last-payment.txt");

    private final RestClient.Builder restClientBuilder;
    private final X402Codec codec;

    ReplayCommand(RestClient.Builder restClientBuilder, X402Codec codec) {
        this.restClientBuilder = restClientBuilder;
        this.codec = codec;
    }

    /** @return {@code true} only if the server answered 402 (the replay was correctly rejected) */
    boolean run(String url, PrintStream out, PrintStream err) throws Exception {
        if (!Files.isRegularFile(LAST_PAYMENT_FILE)) {
            err.println("no stored payment found at " + LAST_PAYMENT_FILE.toAbsolutePath() + "; run `buy` first");
            return false;
        }
        String paymentSignatureHeader = Files.readString(LAST_PAYMENT_FILE).trim();

        PaymentPayload storedPayload;
        try {
            storedPayload = codec.decodePaymentPayload(paymentSignatureHeader);
        } catch (X402CodecException e) {
            err.println("stored payment at " + LAST_PAYMENT_FILE.toAbsolutePath()
                    + " could not be decoded; run `buy` again");
            return false;
        }
        long validBefore =
                Long.parseLong(storedPayload.payload().authorization().validBefore());
        if (Instant.now().getEpochSecond() >= validBefore) {
            out.println("warning: the stored authorization's validity window has already passed; a 402 here would"
                    + " only prove expiry, not replay protection -- run `buy` again first for a"
                    + " meaningful check");
            return false;
        }

        String idempotencyKey = "console-buyer-replay-" + UUID.randomUUID();
        RestClient restClient = restClientBuilder.clone().build();

        try {
            restClient
                    .get()
                    .uri(url)
                    .header(X402PaymentInterceptor.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .header(X402Headers.PAYMENT_SIGNATURE, paymentSignatureHeader)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            out.println("status: " + status);
            printServerErrorReason(e, out);
            if (status == 402) {
                out.println("replay was correctly rejected with 402 (the authorization's nonce was already used)");
                return true;
            }
            err.println("replay was NOT rejected as expected (status " + status + ")");
            return false;
        }
        err.println("replay succeeded -- REPLAY PROTECTION FAILED");
        return false;
    }

    private void printServerErrorReason(RestClientResponseException e, PrintStream out) {
        HttpHeaders headers = e.getResponseHeaders();
        String paymentRequiredHeader = headers == null ? null : headers.getFirst(X402Headers.PAYMENT_REQUIRED);
        if (paymentRequiredHeader == null) {
            return;
        }
        try {
            PaymentRequired paymentRequired = codec.decodePaymentRequired(paymentRequiredHeader);
            out.println("server error: " + SafePrint.of(paymentRequired.error()));
        } catch (X402CodecException ignored) {
            // Nothing more to report; the status line above is already printed.
        }
    }
}
