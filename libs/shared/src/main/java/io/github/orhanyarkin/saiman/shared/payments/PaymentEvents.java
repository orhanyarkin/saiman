package io.github.orhanyarkin.saiman.shared.payments;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.regex.Pattern;

/** Shared validation for the payment event records. */
final class PaymentEvents {

    static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
    static final Pattern TX_HASH = Pattern.compile("0x[0-9a-fA-F]{64}");
    static final Pattern REASON_CODE = Pattern.compile("[a-z0-9_]{1,64}");

    private PaymentEvents() {}

    static void requireCommon(Money amount, String payTo, String resource) {
        if (!"USDC".equals(amount.asset()) || amount.decimals() != Money.SIX_DECIMALS || amount.atomicUnits() <= 0) {
            throw new IllegalArgumentException("amount must be positive 6-decimal USDC");
        }
        if (!ADDRESS.matcher(payTo).matches()) {
            throw new IllegalArgumentException("payTo must be a 0x address");
        }
        if (resource.isBlank() || resource.length() > 512) {
            throw new IllegalArgumentException("resource must be 1-512 characters");
        }
    }
}
