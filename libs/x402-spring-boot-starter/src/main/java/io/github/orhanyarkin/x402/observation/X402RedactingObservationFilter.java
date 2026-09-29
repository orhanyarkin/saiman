package io.github.orhanyarkin.x402.observation;

import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.server.RequiresPaymentRegistry;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Drops any observation key-value that could carry x402 payment payloads or signatures, before it
 * ever reaches a trace exporter or a metrics backend (ADR-0006 amendment: the core OpenTelemetry
 * Collector distribution cannot redact attributes, so payment data must never leave the service in
 * the first place).
 *
 * <p>Applies to every {@link Observation} on the {@link io.micrometer.observation.ObservationRegistry}
 * this filter is registered on, in two tiers so it cannot over-redact unrelated observations (e.g.
 * Spring Security's own {@code spring.security.authorization.*} key-values, which legitimately
 * contain the word "authorization" but carry nothing payment-related):
 *
 * <ul>
 *   <li>A key name (case-insensitively) exactly equal to a payment header name ({@code
 *       PAYMENT-SIGNATURE}, {@code PAYMENT-REQUIRED}, {@code PAYMENT-RESPONSE}) is always dropped,
 *       from any observation -- a generic HTTP server/client observation could in principle capture
 *       one of these verbatim if some other part of the application ever turns header capture on.
 *   <li>A key name in this starter's own {@code x402.*} namespace is additionally dropped if it
 *       contains {@code signature}, {@code payload} or {@code authorization} -- a defence against
 *       this starter's own code accidentally adding a key that carries more than the allowlisted
 *       {@link X402ObservationKeys}.
 * </ul>
 *
 * <p>Short-circuits entirely (returns {@code context} unchanged) when a {@link
 * RequiresPaymentRegistry} is available and reports no {@code @RequiresPayment} handlers at all --
 * every other observation in such an application is definitionally unrelated to payments. When no
 * registry is available at all (e.g. a client-only application with no server-side x402 usage),
 * this always runs the checks above rather than assuming there is nothing to redact.
 */
public final class X402RedactingObservationFilter implements ObservationFilter {

    private static final Set<String> DISALLOWED_KEY_NAMES = Set.of(
            X402Headers.PAYMENT_SIGNATURE.toLowerCase(Locale.ROOT),
            X402Headers.PAYMENT_REQUIRED.toLowerCase(Locale.ROOT),
            X402Headers.PAYMENT_RESPONSE.toLowerCase(Locale.ROOT));

    private static final List<String> DISALLOWED_SUBSTRINGS = List.of("signature", "payload", "authorization");
    private static final String X402_NAMESPACE_PREFIX = "x402.";

    private final ObjectProvider<RequiresPaymentRegistry> registryProvider;

    public X402RedactingObservationFilter(ObjectProvider<RequiresPaymentRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        RequiresPaymentRegistry registry = registryProvider.getIfAvailable();
        if (registry != null && !registry.hasPaidHandlers()) {
            return context;
        }
        removeDisallowed(context, true);
        removeDisallowed(context, false);
        return context;
    }

    private static void removeDisallowed(Observation.Context context, boolean lowCardinality) {
        List<String> toRemove = new ArrayList<>();
        for (KeyValue keyValue :
                lowCardinality ? context.getLowCardinalityKeyValues() : context.getHighCardinalityKeyValues()) {
            if (isDisallowed(keyValue.getKey())) {
                toRemove.add(keyValue.getKey());
            }
        }
        for (String key : toRemove) {
            if (lowCardinality) {
                context.removeLowCardinalityKeyValue(key);
            } else {
                context.removeHighCardinalityKeyValue(key);
            }
        }
    }

    private static boolean isDisallowed(String keyName) {
        String normalized = keyName.toLowerCase(Locale.ROOT);
        if (DISALLOWED_KEY_NAMES.contains(normalized)) {
            return true;
        }
        if (normalized.startsWith(X402_NAMESPACE_PREFIX)) {
            for (String substring : DISALLOWED_SUBSTRINGS) {
                if (normalized.contains(substring)) {
                    return true;
                }
            }
        }
        return false;
    }
}
