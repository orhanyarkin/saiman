package io.github.orhanyarkin.saiman.evmrpc;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Creates the {@link BaseSepoliaUsdc} client when {@code saiman.chain.rpc-url} is set. Invalid settings or a
 * chain id other than 84532 fail application startup; replace the bean to use a different implementation.
 */
@AutoConfiguration
@EnableConfigurationProperties(ChainProperties.class)
@ConditionalOnProperty("saiman.chain.rpc-url")
public class EvmRpcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(BaseSepoliaUsdc.class)
    BaseSepoliaUsdc baseSepoliaUsdc(ChainProperties properties) {
        return new JsonRpcBaseSepoliaUsdc(properties);
    }
}
