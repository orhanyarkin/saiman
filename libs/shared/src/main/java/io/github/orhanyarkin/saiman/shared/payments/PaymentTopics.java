package io.github.orhanyarkin.saiman.shared.payments;

/** Topic names, {@code <domain>.<event>.v1}. */
public final class PaymentTopics {

    public static final String AUTHORIZED = "payments.authorized.v1";
    public static final String SETTLED = "payments.settled.v1";
    public static final String FAILED = "payments.failed.v1";

    private PaymentTopics() {}
}
