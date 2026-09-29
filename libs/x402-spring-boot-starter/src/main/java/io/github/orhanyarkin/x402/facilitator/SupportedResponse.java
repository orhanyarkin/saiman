package io.github.orhanyarkin.x402.facilitator;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The facilitator's answer to {@code GET /supported}: the (version, scheme, network) combinations
 * it can verify and settle.
 *
 * <p>{@link HttpFacilitatorClient} calls this at startup and requires {@code exact} on {@link
 * io.github.orhanyarkin.x402.core.TestnetAssets#NETWORK} to be present, failing closed otherwise
 * (ADR-0008).
 *
 * @param kinds the supported (version, scheme, network) combinations
 * @param extensions protocol extensions the facilitator supports
 * @param signers addresses the facilitator settles from, keyed by CAIP-2 namespace pattern (e.g.
 *     {@code "eip155:*"})
 */
public record SupportedResponse(
        List<SupportedKind> kinds,
        @Nullable List<Object> extensions,
        @Nullable Map<String, List<String>> signers) {

    public SupportedResponse {
        kinds = kinds == null ? List.of() : List.copyOf(kinds);
    }
}
