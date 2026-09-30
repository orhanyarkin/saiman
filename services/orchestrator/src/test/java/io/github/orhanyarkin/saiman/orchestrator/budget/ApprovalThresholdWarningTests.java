package io.github.orhanyarkin.saiman.orchestrator.budget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApprovalThresholdWarningTests {

    @Test
    void aThresholdBelowThePerRequestMaximumIsFine() {
        assertThat(BudgetSpendGuard.approvalThresholdWarning(10_000, 20_000)).isNull();
    }

    @Test
    void aThresholdAtOrAboveThePerRequestMaximumWarnsThatApprovalsNeverTrigger() {
        assertThat(BudgetSpendGuard.approvalThresholdWarning(20_000, 20_000))
                .contains("no payment can ever need a human approval");
        assertThat(BudgetSpendGuard.approvalThresholdWarning(30_000, 20_000))
                .contains("approval-threshold-atomic")
                .contains("max-amount-per-request");
    }
}
