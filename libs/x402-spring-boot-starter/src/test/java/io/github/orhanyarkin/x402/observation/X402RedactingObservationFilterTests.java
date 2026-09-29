package io.github.orhanyarkin.x402.observation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.facilitator.FacilitatorClient;
import io.github.orhanyarkin.x402.server.RequiresPaymentRegistry;
import io.github.orhanyarkin.x402.server.X402ServerProperties;
import io.micrometer.observation.Observation;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit-level coverage of the review's exact scoping requirement: the payment-header exact-match
 * denylist is global, the {@code x402.*} substring rule is namespace-scoped (so it cannot
 * over-redact e.g. Spring Security's own {@code spring.security.authorization.*} key-values), and
 * the filter demonstrably removes something rather than a test passing vacuously.
 */
class X402RedactingObservationFilterTests {

    /** {@code getIfAvailable()} returns {@code null}: exercises the filter with no registry opinion, i.e. never short-circuited. */
    private static final ObjectProvider<RequiresPaymentRegistry> NO_REGISTRY = new ObjectProvider<>() {
        @Override
        public RequiresPaymentRegistry getObject() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RequiresPaymentRegistry getObject(Object... args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable RequiresPaymentRegistry getIfAvailable() {
            return null;
        }

        @Override
        public @Nullable RequiresPaymentRegistry getIfUnique() {
            return null;
        }
    };

    @Test
    void springSecurityAuthorizationKeysSurviveWhilePaymentHeaderNamesAndX402SignatureKeysAreRemoved() {
        X402RedactingObservationFilter filter = new X402RedactingObservationFilter(NO_REGISTRY);
        Observation.Context context = new Observation.Context();
        context.addLowCardinalityKeyValue(
                io.micrometer.common.KeyValue.of("spring.security.authorization.decision", "granted"));
        context.addLowCardinalityKeyValue(io.micrometer.common.KeyValue.of("PAYMENT-SIGNATURE", "should-be-removed"));
        context.addHighCardinalityKeyValue(
                io.micrometer.common.KeyValue.of("x402.internal.signature.debug", "should-be-removed-too"));
        context.addLowCardinalityKeyValue(io.micrometer.common.KeyValue.of(X402ObservationKeys.OUTCOME, "settled"));

        Observation.Context result = filter.map(context);

        // Removed: the exact payment header name (global) and the x402.* key containing "signature".
        assertThat(result.getLowCardinalityKeyValue("PAYMENT-SIGNATURE")).isNull();
        assertThat(result.getHighCardinalityKeyValue("x402.internal.signature.debug"))
                .isNull();

        // Survives: an unrelated framework key that happens to contain "authorization", and this
        // starter's own allowlisted outcome key.
        assertThat(result.getLowCardinalityKeyValue("spring.security.authorization.decision"))
                .isNotNull();
        assertThat(result.getLowCardinalityKeyValue(X402ObservationKeys.OUTCOME))
                .isNotNull();
    }

    @Test
    void shortCircuitsWhenTheRegistryReportsNoPaidHandlers() {
        RequiresPaymentRegistry emptyRegistry = new RequiresPaymentRegistry(
                Mockito.mock(ObjectProvider.class),
                Mockito.mock(ObjectProvider.class),
                Mockito.mock(FacilitatorClient.class),
                new X402ServerProperties(null, null, 60, null));
        ObjectProvider<RequiresPaymentRegistry> provider = new ObjectProvider<>() {
            @Override
            public RequiresPaymentRegistry getObject() {
                return emptyRegistry;
            }

            @Override
            public RequiresPaymentRegistry getObject(Object... args) {
                return emptyRegistry;
            }

            @Override
            public RequiresPaymentRegistry getIfAvailable() {
                return emptyRegistry;
            }

            @Override
            public RequiresPaymentRegistry getIfUnique() {
                return emptyRegistry;
            }
        };
        X402RedactingObservationFilter filter = new X402RedactingObservationFilter(provider);
        Observation.Context context = new Observation.Context();
        context.addLowCardinalityKeyValue(io.micrometer.common.KeyValue.of("PAYMENT-SIGNATURE", "left-alone"));

        Observation.Context result = filter.map(context);

        // Short-circuited: even a payment-header-named key is left untouched, since this
        // application (per the empty registry) has no paid handlers at all.
        assertThat(result.getLowCardinalityKeyValue("PAYMENT-SIGNATURE")).isNotNull();
    }
}
