package io.github.orhanyarkin.saiman.ledger.reconciliation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.ledger.reconciliation.*} (docs/design/m4-ledger.md).
 *
 * @param interval delay between scheduled runs (also the delay before the first one)
 * @param graceAfterValidBefore an authorization without a reported tx is checked once the safe block is this far
 *     past its {@code validBefore}
 * @param receiptGrace a reported tx without a receipt is {@code TX_NOT_FOUND} once the safe block is this far past
 *     {@code validBefore} (a transfer cannot be mined after it)
 * @param batchSize payments checked per run, least recently checked first
 * @param logSearchBlocks blocks searched for {@code AuthorizationUsed} up to {@code validBefore} (capped by
 *     {@code saiman.chain.max-log-range-blocks}; 3600 blocks is two hours on Base)
 */
@ConfigurationProperties("saiman.ledger.reconciliation")
public record ReconciliationProperties(
        @DefaultValue("5m") Duration interval,
        @DefaultValue("30m") Duration graceAfterValidBefore,
        @DefaultValue("10m") Duration receiptGrace,
        @DefaultValue("50") int batchSize,
        @DefaultValue("3600") long logSearchBlocks) {

    public ReconciliationProperties {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("saiman.ledger.reconciliation.interval must be positive");
        }
        if (graceAfterValidBefore.isNegative() || receiptGrace.isNegative()) {
            throw new IllegalArgumentException("saiman.ledger.reconciliation graces must not be negative");
        }
        if (batchSize < 1 || batchSize > 50) {
            throw new IllegalArgumentException("saiman.ledger.reconciliation.batch-size must be between 1 and 50");
        }
        if (logSearchBlocks < 1) {
            throw new IllegalArgumentException("saiman.ledger.reconciliation.log-search-blocks must be positive");
        }
    }

    ChainReconciler.Settings settings() {
        return new ChainReconciler.Settings(receiptGrace, graceAfterValidBefore);
    }
}
