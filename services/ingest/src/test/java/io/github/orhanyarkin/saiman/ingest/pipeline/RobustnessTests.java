package io.github.orhanyarkin.saiman.ingest.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.ingest.IngestIntegrationTests;
import io.github.orhanyarkin.saiman.ingest.IngestProperties;
import io.github.orhanyarkin.saiman.ingest.RecordingEmbeddingModel;
import io.github.orhanyarkin.saiman.ingest.dlq.DeadLetterRepository;
import io.github.orhanyarkin.saiman.ingest.mkk.FakeMkkServer.Reply;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkClient;
import io.github.orhanyarkin.saiman.ingest.retrieval.RetrievalRepository;
import io.github.orhanyarkin.saiman.ingest.store.CursorRepository;
import io.github.orhanyarkin.saiman.ingest.store.DocumentRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;

class RobustnessTests extends IngestIntegrationTests {

    private static final long LOCK_KEY = 0x5A1A_0001_0000_0001L;
    private static final List<Long> THYAO_DETAILS =
            List.of(1_096_000L, 1_100_000L, 1_101_500L, 1_102_000L); // the first page (1093000-1094000) succeeds

    @Autowired
    private IngestJob job;

    @Autowired
    private MkkClient mkk;

    @Autowired
    private CompanyIngester companies;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private CursorRepository cursors;

    @Autowired
    private DeadLetterRepository deadLetters;

    @Autowired
    private RetrievalRepository retrieval;

    @Autowired
    private IngestProperties properties;

    @Autowired
    private LockConnectionFactory locks;

    @Autowired
    private RestTestClient client;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private RecordingEmbeddingModel model;

    @Test
    void circuitOpenAbortsTheRunAndTheNextRunResumesFromTheCursor() {
        THYAO_DETAILS.forEach(i -> MKK.always("/disclosureDetail/" + i, Reply.status(500)));

        RunReport aborted = job.run();

        assertThat(aborted.aborted()).isTrue();
        assertThat(cursors.find("THYAO")).hasValueSatisfying(c -> {
            assertThat(c.done()).isFalse();
            assertThat(c.index()).isGreaterThan(CompanyIngester.FIRST_INDEX);
        });
        assertThat(count("dead_letter")).isZero(); // an open circuit is not the document's fault
        assertThat(countByStatus("INDEXED")).isEqualTo(2); // the first page was done

        THYAO_DETAILS.forEach(i -> MKK.clearAlways("/disclosureDetail/" + i));
        await().pollDelay(Duration.ofMillis(400)).until(() -> true); // circuit-open-wait is 300 ms in tests

        RunReport resumed = job.run();

        assertThat(resumed.aborted()).isFalse();
        assertThat(resumed.tickersDone()).isEqualTo(2);
        assertThat(countByStatus("INDEXED")).isEqualTo(5); // 3 THYAO + 2 ASELS
        assertThat(cursors.find("THYAO"))
                .hasValueSatisfying(c -> assertThat(c.done()).isTrue());
    }

    @Test
    void anUnlockFailureNeverLeavesTheLockHeld() {
        AtomicBoolean closed = new AtomicBoolean();
        LockConnectionFactory failingUnlock = () -> {
            Connection real = locks.open();
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")
                                && String.valueOf(args[0]).contains("pg_advisory_unlock")) {
                            throw new SQLException("unlock failed");
                        }
                        if (method.getName().equals("close")) {
                            closed.set(true);
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        };
        IngestJob brittle = new IngestJob(mkk, companies, documents, failingUnlock, properties);

        RunReport report = brittle.run();

        assertThat(report.alreadyRunning()).isFalse();
        assertThat(closed).isTrue(); // the dedicated session was closed, which releases the lock
        assertThat(job.isRunning()).isFalse();
        assertThat(job.run().alreadyRunning()).isFalse();
    }

    @Test
    void deadLetterRowsNeverHoldDocumentText() {
        String secret = "GIZLI CHUNK METNI 4242";
        Throwable violation = catchThrowable(() -> jdbc.sql("INSERT INTO chunk (id, content, metadata, embedding)"
                        + " VALUES ('kap:1:0000', :c, '{\"documentId\":\"kap:1\",\"ticker\":\"X\"}'::jsonb, NULL)")
                .param("c", secret)
                .update());
        assertThat(violation).hasMessageContaining(secret); // the database really does quote the row

        deadLetters.park("1", "embed", violation, 3);

        assertThat(jdbc.sql("SELECT error_message FROM dead_letter WHERE external_id = '1'")
                        .query(String.class)
                        .single())
                .isEqualTo("SQLState 23502")
                .doesNotContain(secret);
        assertThat(jdbc.sql("SELECT error_class FROM dead_letter WHERE external_id = '1'")
                        .query(String.class)
                        .single())
                .isNotEmpty();
    }

    @Test
    void nonDatabaseErrorsAreStoredByClassOnly() {
        deadLetters.park("2", "normalize", new IllegalStateException("contains GIZLI METIN"), 3);

        assertThat(jdbc.sql("SELECT error_message FROM dead_letter WHERE external_id = '2'")
                        .query(String.class)
                        .single())
                .isEmpty();
    }

    @Test
    void retryDlqAnswers409WhileARunHoldsTheLockAnd202Otherwise() throws Exception {
        try (Connection other = locks.open();
                PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_lock(?)")) {
            lock.setLong(1, LOCK_KEY);
            lock.execute();

            client.post()
                    .uri("/internal/v1/admin/retry-dlq")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409)
                    .expectHeader()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }

        client.post()
                .uri("/internal/v1/admin/retry-dlq")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.reopened")
                .isEqualTo(0);
    }

    @Test
    void theVectorLegIsOrderedByTrueDistance() {
        job.run();
        float[] query = RecordingEmbeddingModel.vector("yönetim kurulu bağımsız üyelerin görevlendirilmesi ilke 7");

        List<String> ranked = transactions.execute(status -> {
            retrieval.enableIterativeScan();
            return retrieval.vectorLeg(query, List.of());
        });

        assertThat(ranked).hasSizeGreaterThan(3);
        List<Double> distances = ranked.stream()
                .map(id -> jdbc.sql("SELECT embedding <=> CAST(:v AS vector) FROM chunk WHERE id = :id")
                        .param("v", RetrievalRepository.vectorLiteral(query))
                        .param("id", id)
                        .query(Double.class)
                        .single())
                .toList();
        assertThat(distances).isSorted();
        assertThat(model.calls()).isPositive();
    }
}
