package io.github.orhanyarkin.saiman.evals.answers;

/** The seller rejected the service token (401/403): the whole answer tier stops. */
public class SellerAuthException extends RuntimeException {

    public SellerAuthException(int status) {
        super("seller-api rejected the evals service token (HTTP " + status
                + "); check the secret seller_service_token_evals and SAIMAN_AUTH_SERVICE_TOKENS_EVALS_SHA256");
    }
}
