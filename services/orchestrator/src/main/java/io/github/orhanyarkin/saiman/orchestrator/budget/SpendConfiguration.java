package io.github.orhanyarkin.saiman.orchestrator.budget;

import io.github.orhanyarkin.x402.client.X402ClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds the spend-control and run-limit properties. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SpendProperties.class, RunLimitsProperties.class, HeldResolutionProperties.class})
class SpendConfiguration {

    /** Copies only the limit out of the key-bearing client properties. */
    @Bean
    SpendLimitsView spendLimitsView(X402ClientProperties x402) {
        return new SpendLimitsView(x402.maxAmountPerRequest());
    }
}
