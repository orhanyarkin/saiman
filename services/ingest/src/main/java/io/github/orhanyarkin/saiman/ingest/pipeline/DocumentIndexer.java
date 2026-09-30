package io.github.orhanyarkin.saiman.ingest.pipeline;

import io.github.orhanyarkin.saiman.ingest.IngestProperties;
import io.github.orhanyarkin.saiman.ingest.chunking.DisclosureChunker;
import io.github.orhanyarkin.saiman.ingest.chunking.DisclosureTextExtractor;
import io.github.orhanyarkin.saiman.ingest.dlq.DeadLetterRepository;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkCircuitOpenException;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkClient;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.DisclosureDetail;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.DisclosureSummary;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.HtmlMessage;
import io.github.orhanyarkin.saiman.ingest.store.DocumentRepository;
import io.github.orhanyarkin.saiman.ingest.store.DocumentRepository.NewDocument;
import io.github.orhanyarkin.saiman.ingest.store.DocumentRepository.Stored;
import io.github.orhanyarkin.saiman.ingest.store.DocumentStatus;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Indexes one disclosure. Idempotent by construction (ADR-0012):
 *
 * <ol>
 *   <li>a terminal document is skipped without any call;
 *   <li>the content hash is compared <em>before</em> embedding: if the same content already has
 *       all its chunks, only the finalize step runs and the embedding model is never called;
 *   <li>chunk ids are deterministic, so a crashed run re-upserts the same rows;
 *   <li>embedding happens outside any database transaction; finalize (status, surplus chunks,
 *       supersession) is one short transaction.
 * </ol>
 */
@Component
public class DocumentIndexer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexer.class);
    private static final DateTimeFormatter KAP_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    private final MkkClient mkk;
    private final DocumentRepository documents;
    private final DeadLetterRepository deadLetters;
    private final VectorStore vectorStore;
    private final DisclosureChunker chunker;
    private final TransactionTemplate transactions;
    private final ObservationRegistry observations;
    private final IngestMetrics metrics;
    private final int maxAttempts;

    public DocumentIndexer(
            MkkClient mkk,
            DocumentRepository documents,
            DeadLetterRepository deadLetters,
            VectorStore vectorStore,
            DisclosureChunker chunker,
            TransactionTemplate transactions,
            ObservationRegistry observations,
            IngestMetrics metrics,
            IngestProperties properties) {
        this.mkk = mkk;
        this.documents = documents;
        this.deadLetters = deadLetters;
        this.vectorStore = vectorStore;
        this.chunker = chunker;
        this.transactions = transactions;
        this.observations = observations;
        this.metrics = metrics;
        this.maxAttempts = properties.maxAttempts();
    }

    /**
     * Processes disclosure {@code index}; failures are counted and the document is retried until
     * {@code max-attempts}, then parked in the DLQ. Only a tripped circuit breaker escapes: that
     * is not the document's fault and must stop the run.
     */
    public Outcome process(String ticker, DisclosureSummary summary) {
        long index = summary.disclosureIndex();
        String id = DocumentRepository.documentId(index);
        Optional<Stored> existing = documents.find(id);
        if (existing.isPresent()
                && (existing.get().status().terminal() || existing.get().status() == DocumentStatus.FAILED)) {
            return record(Outcome.SKIPPED);
        }
        if (existing.isEmpty()) {
            documents.upsertPending(stub(ticker, summary));
        }
        while (true) {
            Stage stage = new Stage();
            try {
                return record(attempt(ticker, summary, stage));
            } catch (MkkCircuitOpenException e) {
                throw e;
            } catch (RuntimeException e) {
                int attempts = documents.recordFailure(id, maxAttempts);
                // Log the class only: messages of unexpected errors could quote document content.
                log.warn(
                        "Disclosure {} failed at stage {} (attempt {}): {}",
                        index,
                        stage.name,
                        attempts,
                        e.getClass().getSimpleName());
                if (attempts >= maxAttempts) {
                    deadLetters.park(Long.toString(index), stage.name, e, attempts);
                    return record(Outcome.FAILED);
                }
            }
        }
    }

    private Outcome attempt(String ticker, DisclosureSummary summary, Stage stage) {
        long index = summary.disclosureIndex();
        String id = DocumentRepository.documentId(index);

        stage.name = "fetch";
        DisclosureDetail detail = observe("fetch", () -> mkk.disclosureDetail(index));
        String reason = detail.disclosureReason() == null
                ? "NEW"
                : detail.disclosureReason().strip().toUpperCase(Locale.ROOT);
        String related = relatedId(detail.relatedDisclosureIndex());
        String title = title(detail, summary);
        Instant publishedAt = parseTime(detail.time());

        if (reason.equals("CANC")) {
            if (related != null) {
                documents.markSuperseded(related);
            }
            documents.upsertTerminal(
                    newDoc(ticker, summary, detail, title, reason, related, publishedAt, null),
                    DocumentStatus.SUPERSEDED);
            return Outcome.CANCELLATION;
        }

        stage.name = "normalize";
        String text = observe("normalize", () -> bodyText(detail));
        String hash = sha256(title + "\n" + text);

        stage.name = "chunk";
        List<String> chunks = observe("chunk", () -> chunker.chunk(title, text));
        List<String> chunkIds = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            chunkIds.add(chunkId(index, i));
        }

        Optional<Stored> before = documents.find(id);
        boolean sameContent = before.map(Stored::contentHash).map(hash::equals).orElse(false);
        boolean complete = sameContent && documents.countChunks(id) == chunks.size();
        documents.upsertPending(newDoc(ticker, summary, detail, title, reason, related, publishedAt, hash));

        if (!complete && !chunks.isEmpty()) {
            stage.name = "embed";
            List<Document> toStore = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("documentId", id);
                metadata.put("ticker", ticker);
                metadata.put("disclosureIndex", index);
                metadata.put("chunkNumber", i);
                metadata.put("title", title);
                metadata.put("sourceUrl", "https://www.kap.org.tr/tr/Bildirim/" + index);
                metadata.put("publishedAt", publishedAt.toString());
                toStore.add(new Document(chunkIds.get(i), chunks.get(i), metadata));
            }
            observeRun("embed", () -> vectorStore.add(toStore));
            metrics.chunks(toStore.size());
        }

        stage.name = "finalize";
        transactions.executeWithoutResult(status -> {
            documents.finalizeIndexed(id, chunkIds);
            if (related != null && (reason.equals("CORR") || reason.equals("UPD"))) {
                documents.markSuperseded(related);
            }
            deadLetters.clear(Long.toString(index));
        });
        return complete ? Outcome.REPAIRED : Outcome.INDEXED;
    }

    private Outcome record(Outcome outcome) {
        metrics.document(outcome.name().toLowerCase(Locale.ROOT));
        return outcome;
    }

    private <T> T observe(String stage, Supplier<T> work) {
        Observation observation =
                Observation.createNotStarted("ingest.stage", observations).lowCardinalityKeyValue("stage", stage);
        return observation.observe(work);
    }

    private void observeRun(String stage, Runnable work) {
        Observation.createNotStarted("ingest.stage", observations)
                .lowCardinalityKeyValue("stage", stage)
                .observe(work);
    }

    static String chunkId(long disclosureIndex, int chunkNumber) {
        return "kap:%d:%04d".formatted(disclosureIndex, chunkNumber);
    }

    private static String bodyText(DisclosureDetail detail) {
        List<HtmlMessage> messages = detail.htmlMessages();
        if (messages == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (HtmlMessage message : messages) {
            String tr = message.tr();
            if (tr != null && !tr.isBlank()) {
                String text = DisclosureTextExtractor.fromBase64Html(tr);
                if (!text.isBlank()) {
                    parts.add(text);
                }
            }
        }
        return String.join("\n", parts);
    }

    private static String title(DisclosureDetail detail, DisclosureSummary summary) {
        String subject = detail.subject() == null ? null : detail.subject().tr();
        if (subject != null && !subject.isBlank()) {
            return subject.strip();
        }
        return summary.title() == null
                ? "KAP " + summary.disclosureIndex()
                : summary.title().strip();
    }

    private static @Nullable String relatedId(@Nullable String value) {
        if (value == null || !value.strip().matches("\\d{1,10}")) {
            return null;
        }
        return DocumentRepository.documentId(Long.parseLong(value.strip()));
    }

    private static Instant parseTime(@Nullable String time) {
        if (time == null) {
            throw new IllegalArgumentException("disclosure has no publication time");
        }
        return LocalDateTime.parse(time.strip(), KAP_TIME).atZone(ISTANBUL).toInstant();
    }

    private static NewDocument stub(String ticker, DisclosureSummary summary) {
        return new NewDocument(
                summary.disclosureIndex(),
                ticker,
                summary.title() == null ? "KAP " + summary.disclosureIndex() : summary.title(),
                summary.disclosureClass() == null ? "" : summary.disclosureClass(),
                summary.disclosureType() == null ? "" : summary.disclosureType(),
                "NEW",
                null,
                Instant.EPOCH,
                null);
    }

    private static NewDocument newDoc(
            String ticker,
            DisclosureSummary summary,
            DisclosureDetail detail,
            String title,
            String reason,
            @Nullable String related,
            Instant publishedAt,
            @Nullable String hash) {
        String relatedIndex = related == null ? null : related.substring(related.indexOf(':') + 1);
        return new NewDocument(
                summary.disclosureIndex(),
                ticker,
                title,
                firstNonNull(detail.disclosureClass(), summary.disclosureClass()),
                firstNonNull(detail.disclosureType(), summary.disclosureType()),
                reason,
                relatedIndex,
                publishedAt,
                hash);
    }

    private static String firstNonNull(@Nullable String a, @Nullable String b) {
        return a != null ? a : (b != null ? b : "");
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The stage a failure happened in; recorded in the DLQ row. */
    private static final class Stage {
        String name = "fetch";
    }
}
