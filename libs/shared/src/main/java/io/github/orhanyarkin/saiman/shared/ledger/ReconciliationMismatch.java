package io.github.orhanyarkin.saiman.shared.ledger;

import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code ledger.reconciliation-mismatch.v1}: the books disagree with the chain for one payment. When the ledger
 * moved the difference to suspense, {@code adjustmentEntryId} names that entry.
 */
public record ReconciliationMismatch(
        EventMetadata meta,
        UUID mismatchId,
        UUID reconciliationRunId,
        UUID paymentId,
        MismatchKind kind,
        @Nullable Money ledgerAmount,
        @Nullable Money chainAmount,
        @Nullable String reportedTxHash,
        @Nullable String chainTxHash,
        @Nullable UUID adjustmentEntryId) {}
