package io.github.orhanyarkin.saiman.ledger.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The outbound seller-api client reconciliation uses to corroborate credit notes (ADR-0021). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SellerProperties.class)
class SellerClientConfiguration {

    @Bean
    SellerCreditNoteClient sellerCreditNoteClient(
            SellerProperties properties, ObjectProvider<ObservationRegistry> observations, MeterRegistry meters) {
        return new RestSellerCreditNoteClient(
                properties, observations.getIfAvailable(() -> ObservationRegistry.NOOP), meters);
    }
}
