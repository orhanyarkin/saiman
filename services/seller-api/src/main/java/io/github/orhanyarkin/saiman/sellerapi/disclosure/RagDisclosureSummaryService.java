package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import io.github.orhanyarkin.saiman.modelrouter.DataClass;
import io.github.orhanyarkin.saiman.modelrouter.Tier;
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
 * therefore skips the model call, not the (cheap) retrieval. Ingest or model trouble surfaces as
 * an exception mapped to a non-2xx response, never as fixture data inside a paid response.
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
    public DisclosureSummaryResponse summaryFor(String ticker) {
        RetrieveResponse retrieved = ingest.retrieve(new RetrieveRequest(QUERY, List.of(ticker), TOP_K));
        List<RetrievedChunk> excerpts = GroundedGenerator.usable(retrieved.chunks(), ticker);
        if (excerpts.isEmpty()) {
            throw new TickerNotFoundException(ticker);
        }
        String version = retrieved.corpusVersion();
        return cache.get(ticker, version).orElseGet(() -> {
            DisclosureSummaryResponse summary = generate(ticker, excerpts);
            cache.put(ticker, version, summary);
            return summary;
        });
    }

    private DisclosureSummaryResponse generate(String ticker, List<RetrievedChunk> excerpts) {
        GroundedGenerator.Reply reply =
                generator.generate(Tier.TIER1, DataClass.PUBLIC, "summary", excerpts, "TASK", TASK);
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
