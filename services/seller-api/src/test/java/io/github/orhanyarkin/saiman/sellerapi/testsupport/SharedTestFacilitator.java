package io.github.orhanyarkin.saiman.sellerapi.testsupport;

import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * One JVM-wide {@link FakeFacilitator}, for every {@code @SpringBootTest} in this module that boots
 * the full application context but is not itself testing the x402 payment flow (e.g. the Actuator
 * tests).
 *
 * <p>{@code DisclosureSummaryController} being a {@code @RequiresPayment} handler means {@code
 * RequiresPaymentRegistry} calls the configured facilitator's {@code /supported} at startup for
 * <em>every</em> context refresh in this module (docs/design/m1-x402.md, ADR-0008: fail closed, no
 * {@code enabled} flag) -- so every such test needs a reachable one. Deliberately never closed:
 * it is a lightweight loopback JDK {@code HttpServer} and the test JVM is torn down at the end of
 * the Gradle test task anyway; closing it in one test class's {@code @AfterAll} would break every
 * other test class sharing it. A test that actually exercises payment (settle, replay, failure
 * injection) should use its own dedicated {@link FakeFacilitator} instance instead, so injected
 * failures and call counts cannot leak across test classes -- see {@code
 * DisclosureSummaryEndpointTests}.
 */
public final class SharedTestFacilitator {

    /** A valid, non-zero, non-USDC test payout address; not used to receive any real payment. */
    public static final String PAY_TO = "0x1111111111111111111111111111111111111111";

    private static final FakeFacilitator INSTANCE = new FakeFacilitator();

    private SharedTestFacilitator() {}

    /** Registers {@code x402.server.pay-to} and {@code x402.server.facilitator.url} against this shared instance. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("x402.server.pay-to", () -> PAY_TO);
        registry.add("x402.server.facilitator.url", INSTANCE::url);
    }
}
