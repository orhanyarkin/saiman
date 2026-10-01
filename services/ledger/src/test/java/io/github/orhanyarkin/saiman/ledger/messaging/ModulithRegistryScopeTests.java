package io.github.orhanyarkin.saiman.ledger.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.RegistryProbe;
import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.ledger.EntryPosted;
import io.github.orhanyarkin.saiman.shared.ledger.PostingLine;
import io.github.orhanyarkin.saiman.shared.ledger.Side;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0016's registry scope, as wired by libs/eventing: only {@code @ApplicationModuleListener}s (here: Modulith's
 * Kafka externalizer) get a row in {@code event_publication}; a plain {@code @TransactionalEventListener} is
 * in-process fan-out and is not persisted. Rows are written at publish time inside the transaction, so they are
 * inspected before the (rolled-back) transaction ends; nothing reaches Kafka.
 */
@LedgerIntegrationTest
class ModulithRegistryScopeTests {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RegistryProbe probe;

    @Test
    void onlyTheExternalizedEventIsPersistedInTheRegistry() {
        String pingId = UUID.randomUUID().toString();
        EntryPosted entryPosted = entryPosted();

        List<String> persistedTypes = tx.execute(status -> {
            events.publishEvent(new RegistryProbe.Ping(pingId));
            events.publishEvent(entryPosted);
            List<String> types = jdbc.sql("""
                            SELECT event_type FROM event_publication
                             WHERE serialized_event LIKE :entry OR serialized_event LIKE :ping
                            """)
                    .param("entry", "%" + entryPosted.entryId() + "%")
                    .param("ping", "%" + pingId + "%")
                    .query(String.class)
                    .list();
            status.setRollbackOnly();
            return types;
        });

        assertThat(persistedTypes).containsExactly(EntryPosted.class.getName());
        assertThat(probe.received()).doesNotContain(pingId); // rolled back: AFTER_COMMIT listeners never ran
    }

    @Test
    void plainTransactionalListenerStillRunsAfterCommit() {
        String pingId = UUID.randomUUID().toString();

        tx.executeWithoutResult(status -> events.publishEvent(new RegistryProbe.Ping(pingId)));

        assertThat(probe.received()).contains(pingId);
        assertThat(jdbc.sql("SELECT count(*) FROM event_publication WHERE serialized_event LIKE :ping")
                        .param("ping", "%" + pingId + "%")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private static EntryPosted entryPosted() {
        UUID entryId = UUID.randomUUID();
        Money amount = Money.usdc(1);
        return new EntryPosted(
                new EventMetadata(entryId.toString(), Instant.now(), "ledger", "registry-scope-test"),
                entryId,
                UUID.randomUUID(),
                "ADJUSTMENT",
                List.of(
                        new PostingLine("platform:suspense:usdc", Side.DEBIT, amount),
                        new PostingLine("platform:suspense:usdc", Side.CREDIT, amount)),
                Instant.now());
    }
}
