package io.github.orhanyarkin.saiman.ingest.mkk;

/**
 * MKK rejected our credential, sender IP or token (401/403 or ER001-ER004, ER006, ER007). The
 * message names the status and code so the operator can act; it never contains the credential.
 */
public class MkkCredentialException extends MkkHttpException {

    private static final long serialVersionUID = 1L;

    public MkkCredentialException(MkkHttpException cause) {
        super(cause.status(), cause.errorCode(), cause.retryAfter(), describe(cause));
    }

    private static String describe(MkkHttpException cause) {
        String base = "MKK request failed with status " + cause.status();
        return cause.errorCode() == null
                ? base
                : base + " (" + cause.errorCode() + "): credential or network configuration problem";
    }
}
