package io.github.orhanyarkin.saiman.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.evmrpc.BaseSepoliaUsdc;
import io.github.orhanyarkin.saiman.evmrpc.JsonRpcBaseSepoliaUsdc;
import io.github.orhanyarkin.saiman.evmrpc.UsdcReceipt;
import io.github.orhanyarkin.saiman.evmrpc.UsdcTransfer;
import io.github.orhanyarkin.saiman.ledger.TestcontainersConfiguration;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.payments.AuthorizationRef;
import io.github.orhanyarkin.saiman.shared.payments.Book;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.github.orhanyarkin.saiman.shared.payments.SettlementEvidence;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Live check against the public Base Sepolia RPC (free, no key): the M3 payment that really settled is booked as
 * the buyer reported it, reconciled with the real JSON-RPC client, and must come out MATCHED. Excluded from
 * {@code check}; run with {@code ./gradlew :services:ledger:testnetTest}.
 */
@Tag("testnet")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class BaseSepoliaReconciliationTests {

    private static final String TX = "0x68592d03715426a9a3aea48c4f50df430e6e10ad46962d9ab0c7c532a73bb090";
    private static final String BUYER = "0xdd542d774e0c0e546d76721396c69e113a644795";
    private static final String USDC = "0x036cbd53842c5426634e7929541ec2318f3dcf7e";

    @Autowired
    private BaseSepoliaUsdc chain;

    @Autowired
    private PaymentLedgerService ledger;

    @Autowired
    private ReconciliationService service;

    @Autowired
    private ReconciliationReports reports;

    @Test
    void realM3SettlementIsMatched() {
        assertThat(chain).isInstanceOf(JsonRpcBaseSepoliaUsdc.class);
        UsdcReceipt receipt = chain.receipt(TX).orElseThrow();
        String nonce = receipt.authorizationsUsed().stream()
                .filter(a -> a.startsWith(BUYER + ":"))
                .map(a -> a.substring(BUYER.length() + 1))
                .findFirst()
                .orElseThrow();
        UsdcTransfer transfer = receipt.transfers().stream()
                .filter(t -> t.from().toLowerCase(Locale.ROOT).equals(BUYER))
                .findFirst()
                .orElseThrow();
        assertThat(transfer.value()).isEqualTo(20_000);

        // validBefore is in the calldata, not the logs; any past value works because the receipt decides.
        var authorization = new AuthorizationRef(AuthorizationRef.BASE_SEPOLIA, USDC, BUYER, nonce, 1_700_000_000L);
        ledger.record(
                PaymentFact.of(new PaymentSettled(
                        new EventMetadata(UUID.randomUUID().toString(), Instant.now(), "orchestrator", "testnet"),
                        authorization,
                        Money.usdc(20_000),
                        transfer.to(),
                        "http://seller-api:8081/v1/disclosures/ASELS/summary",
                        Book.BUYER,
                        TX,
                        SettlementEvidence.FACILITATOR,
                        UUID.randomUUID(),
                        UUID.randomUUID())),
                PaymentTopics.SETTLED);

        UUID runId = service.runNow().orElseThrow();
        ReconciliationReport report = reports.report(runId).orElseThrow();

        UUID paymentId = PaymentProjection.paymentId(authorization.paymentKey());
        assertThat(report.status()).isEqualTo("COMPLETED");
        assertThat(report.items())
                .filteredOn(i -> i.paymentId().equals(paymentId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("MATCHED");
                    assertThat(item.chainState()).isEqualTo("USED");
                    assertThat(item.txHash()).isEqualTo(TX);
                });
    }
}
