package io.github.orhanyarkin.saiman.orchestrator.spendtest;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.evm.PaymentSigner;
import io.github.orhanyarkin.x402.testing.TestWallets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A spy signer for tests: the starter's interceptor signs through it (it replaces the starter's
 * key-backed signer bean), so a test can prove a payment was never signed. Test wallet, no funds.
 */
public final class CountingSigner implements PaymentSigner {

    private final PaymentSigner delegate = TestWallets.PAYER;
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public String address() {
        return delegate.address();
    }

    @Override
    public String signTransferWithAuthorization(Eip3009Authorization authorization) {
        calls.incrementAndGet();
        return delegate.signTransferWithAuthorization(authorization);
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        calls.set(0);
    }
}
