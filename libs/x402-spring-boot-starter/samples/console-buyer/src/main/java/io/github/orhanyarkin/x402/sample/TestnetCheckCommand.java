package io.github.orhanyarkin.x402.sample;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.ExactEvmPayload;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.VerifyResponse;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.evm.Eip3009TypedData;
import io.github.orhanyarkin.x402.evm.PaymentSigner;
import java.io.PrintStream;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@code testnet-check} command: a read-only conformance probe against the public facilitator.
 *
 * <p>Builds a self-transfer (payer pays itself) of the smallest possible amount with a short
 * validity window, signs it, and calls {@code POST /verify} only — never {@code /settle}, so no
 * funds move even on success. The request body shape ({@code {x402Version, paymentPayload,
 * paymentRequirements}}) is from specs/x402-specification-v2.md section 7 at commit {@code
 * c84154b5d6a31d77fd5b9dbb01213053fd9cb9eb}; T2's {@code FacilitatorClient} is server-side and not
 * available to this standalone build, so this talks to the facilitator directly with {@link
 * X402Codec} and a plain {@link RestClient}.
 */
@Component
final class TestnetCheckCommand {

    private static final String VERIFY_URL = "https://x402.org/facilitator/verify";
    // Matches X402PaymentInterceptor's own back-date: EIP-3009 requires block.timestamp >
    // validAfter, and real clocks (this one included -- WSL2's clock can drift) are never exactly
    // synchronised with the chain, so a small skew is generous but a five-second one risks a
    // spurious rejection.
    private static final long CLOCK_SKEW_SECONDS = 600;
    private static final long VALIDITY_SECONDS = 60;
    private static final String INSUFFICIENT_FUNDS = "insufficient_funds";

    private final ObjectProvider<PaymentSigner> signerProvider;
    private final X402Codec codec;
    private final RestClient.Builder restClientBuilder;

    TestnetCheckCommand(
            ObjectProvider<PaymentSigner> signerProvider, X402Codec codec, RestClient.Builder restClientBuilder) {
        this.signerProvider = signerProvider;
        this.codec = codec;
        this.restClientBuilder = restClientBuilder;
    }

    boolean run(PrintStream out, PrintStream err) {
        PaymentSigner signer = signerProvider.getIfAvailable();
        if (signer == null) {
            err.println("no buyer key configured: set X402_BUYER_PRIVATE_KEY, or run `new-wallet` first");
            return false;
        }

        PaymentRequirements requirements = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                "1",
                TestnetAssets.USDC_ADDRESS,
                signer.address(),
                (int) VALIDITY_SECONDS,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));

        Instant now = Instant.now();
        Eip3009Authorization authorization = new Eip3009Authorization(
                signer.address(),
                signer.address(),
                "1",
                Long.toString(Math.max(now.getEpochSecond() - CLOCK_SKEW_SECONDS, 0)),
                Long.toString(now.getEpochSecond() + VALIDITY_SECONDS),
                Eip3009TypedData.randomNonce());
        String signature = signer.signTransferWithAuthorization(authorization);
        PaymentPayload paymentPayload =
                new PaymentPayload(2, null, requirements, new ExactEvmPayload(signature, authorization), null);
        VerifyRequest verifyRequest = new VerifyRequest(2, paymentPayload, requirements);

        String responseBody = restClientBuilder
                .clone()
                .build()
                .post()
                .uri(VERIFY_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(codec.writeJson(verifyRequest))
                .retrieve()
                .body(String.class);
        VerifyResponse response = codec.readJson(responseBody, VerifyResponse.class);

        out.println("isValid: " + response.isValid());
        out.println("invalidReason: " + SafePrint.of(response.invalidReason()));
        out.println("invalidMessage: " + SafePrint.of(response.invalidMessage()));
        if (response.isValid()) {
            return true;
        }
        if (INSUFFICIENT_FUNDS.equals(response.invalidReason())) {
            // Expected for a freshly generated wallet: the signature and request shape are still
            // conformant, this wallet just has no testnet USDC yet.
            out.println("(insufficient_funds is expected for an unfunded wallet; treating this as a pass)");
            return true;
        }
        return false;
    }

    /** Section 7 request body: identical shape for {@code /verify} and {@code /settle}. */
    private record VerifyRequest(
            int x402Version, PaymentPayload paymentPayload, PaymentRequirements paymentRequirements) {}
}
