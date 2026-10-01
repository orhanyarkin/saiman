package io.github.orhanyarkin.saiman.orchestrator.budget;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.orchestrator.held-resolution.*} (ADR-0018). The resolver runs only when a {@code BaseSepoliaUsdc}
 * client exists ({@code saiman.chain.rpc-url} is set).
 *
 * @param interval delay between passes
 * @param batchSize HELD intents examined per pass
 * @param logWindowBlocks blocks before {@code validBefore} searched for the {@code AuthorizationUsed} log (one
 *     {@code eth_getLogs} chunk at most 1000 blocks; Base Sepolia makes a block every 2 s)
 */
@ConfigurationProperties("saiman.orchestrator.held-resolution")
public record HeldResolutionProperties(
        @DefaultValue("2m") Duration interval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("300") int logWindowBlocks) {

    public HeldResolutionProperties {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("saiman.orchestrator.held-resolution.interval must be positive");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("saiman.orchestrator.held-resolution.batch-size must be 1-500");
        }
        if (logWindowBlocks < 1 || logWindowBlocks > 900) {
            throw new IllegalArgumentException("saiman.orchestrator.held-resolution.log-window-blocks must be 1-900");
        }
    }
}
