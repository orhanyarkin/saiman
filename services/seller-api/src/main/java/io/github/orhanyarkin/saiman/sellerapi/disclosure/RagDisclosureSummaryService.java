package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import io.github.orhanyarkin.saiman.modelrouter.DataClass;
import io.github.orhanyarkin.saiman.modelrouter.Tier;
import io.github.orhanyarkin.saiman.sellerapi.llm.Deadline;
import io.github.orhanyarkin.saiman.sellerapi.retrieval.IngestClient;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveRequest;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveResponse;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * RAG-backed {@link DisclosureSummaryService} ({@code seller.disclosures.source=rag}): retrieves the
 * newest material-event and governance excerpts for the ticker from ingest and has the model
 * router ({@link Tier#TIER1}, {@link DataClass#PUBLIC}: the input is public KAP text only) write a
 * cited summary.
 *
 * <p>Retrieval runs on every call because its {@code corpusVersion} is the cache key; a cache hit
 * therefore skips the model call, not the (cheap) retrieval. Unknown tickers are refused before
 * retrieval (which embeds the query and is paid). Concurrent misses for the same key share one
 * generation and a failed generation is remembered briefly (see {@link
 * DisclosureSummaryCache#getOrGenerate}). Ingest or model trouble surfaces as an exception mapped
 * to a non-2xx response, never as fixture data inside a paid response.
 */
@Service
@ConditionalOnProperty(name = "seller.disclosures.source", havingValue = "rag")
class RagDisclosureSummaryService implements DisclosureSummaryService {

    static final String DATA_SOURCE = "kap-rag";
    static final int TOP_K = 6;

    /** Fixed retrieval query: latest material-event and corporate-governance disclosures. */
    static final String QUERY = "Şirketin en son özel durum açıklamaları ve kurumsal yönetim uyum bildirimleri";

    private static final String TASK =
            "Summarise in at most six sentences the most recent material-event and corporate-governance"
                    + " disclosures in the excerpts above.";

    private final IngestClient ingest;
    private final GroundedGenerator generator;
    private final DisclosureSummaryCache cache;

    RagDisclosureSummaryService(IngestClient ingest, GroundedGenerator generator, DisclosureSummaryCache cache) {
        this.ingest = ingest;
        this.generator = generator;
        this.cache = cache;
    }

    @Override
    public DisclosureSummaryResponse summaryFor(String ticker, Deadline deadline) {
        // Run slot first (before any ingest call), see DisclosureAnswerService#answer. A cache hit
        // holds it briefly too; its settlement gives the unsettled slot straight back.
        return generator.withRunSlot(() -> summaryWithSlot(ticker, deadline));
    }

    private DisclosureSummaryResponse summaryWithSlot(String ticker, Deadline deadline) {
        if (ingest.tickers().stream().noneMatch(t -> ticker.equals(t.ticker()))) {
            throw new TickerNotFoundException(ticker);
        }
        RetrieveResponse retrieved = ingest.retrieve(new RetrieveRequest(QUERY, List.of(ticker), TOP_K));
        List<RetrievedChunk> excerpts = GroundedGenerator.usable(retrieved.chunks(), ticker);
        if (excerpts.isEmpty()) {
            throw new TickerNotFoundException(ticker);
        }
        return cache.getOrGenerate(
                ticker,
                retrieved.corpusVersion(),
                deadline,
                () -> generator.requireTimeForModel(deadline),
                () -> generate(ticker, excerpts, deadline));
    }

    private DisclosureSummaryResponse generate(String ticker, List<RetrievedChunk> excerpts, Deadline deadline) {
        GroundedGenerator.Reply reply =
                generator.generate(Tier.TIER1, DataClass.PUBLIC, "summary", excerpts, "TASK", TASK, deadline);
        List<RetrievedChunk> cited = GroundedGenerator.validCitations(excerpts, reply.citedChunkIds());
        if (cited.isEmpty()) {
            throw new InsufficientCitationsException();
        }
        List<DisclosureSummaryResponse.Citation> citations = cited.stream()
                .map(chunk ->
                        new DisclosureSummaryResponse.Citation(chunk.chunkId(), chunk.sourceUrl(), chunk.retrievedAt()))
                .toList();
        return new DisclosureSummaryResponse(ticker, reply.text(), citations, DATA_SOURCE);
    }
}
