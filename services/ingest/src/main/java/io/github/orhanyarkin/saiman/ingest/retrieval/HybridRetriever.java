package io.github.orhanyarkin.saiman.ingest.retrieval;

import io.github.orhanyarkin.saiman.modelrouter.DataClass;
import io.github.orhanyarkin.saiman.modelrouter.ModelRouter;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveRequest;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveResponse;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hybrid retrieval: a vector leg (query embedded through the router, {@link DataClass#INTERNAL}
 * because it derives from a buyer's question) and a Turkish full-text leg, each top 40, fused with
 * reciprocal rank fusion. The query embedding is a network call, so it happens before the read
 * transaction opens. Query text is never logged.
 */
@Service
public class HybridRetriever {

    private final ModelRouter router;
    private final RetrievalRepository repository;
    private final TransactionTemplate readOnly;
    private final ObservationRegistry observations;

    public HybridRetriever(
            ModelRouter router,
            RetrievalRepository repository,
            PlatformTransactionManager transactionManager,
            ObservationRegistry observations) {
        this.router = router;
        this.repository = repository;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.observations = observations;
    }

    public RetrieveResponse retrieve(RetrieveRequest request) {
        return Observation.createNotStarted("ingest.retrieve", observations)
                .lowCardinalityKeyValue("tickers", request.tickers().isEmpty() ? "any" : "filtered")
                .observe(() -> doRetrieve(request));
    }

    private RetrieveResponse doRetrieve(RetrieveRequest request) {
        float[] embedding;
        try {
            EmbeddingModel model = router.embeddingModel(DataClass.INTERNAL);
            embedding = model.embed(request.query());
        } catch (RuntimeException e) {
            // Router refusals (daily cap, data class) and provider errors: report the class only.
            throw new RetrievalUnavailableException(e.getClass().getSimpleName());
        }
        List<String> tickers = request.tickers();
        return readOnly.execute(status -> {
            repository.enableIterativeScan();
            List<String> vector = repository.vectorLeg(embedding, tickers);
            List<String> lexical = repository.lexicalLeg(request.query(), tickers);
            List<RrfFusion.Fused> fused = RrfFusion.fuse(vector, lexical, RrfFusion.DEFAULT_K, request.topK());
            Map<String, RetrievedChunk> loaded =
                    repository.load(fused.stream().map(RrfFusion.Fused::id).toList());
            List<RetrievedChunk> chunks = new ArrayList<>(fused.size());
            for (RrfFusion.Fused hit : fused) {
                RetrievedChunk chunk = loaded.get(hit.id());
                if (chunk != null) {
                    chunks.add(new RetrievedChunk(
                            chunk.chunkId(),
                            chunk.ticker(),
                            chunk.source(),
                            chunk.title(),
                            chunk.sourceUrl(),
                            chunk.publishedAt(),
                            chunk.retrievedAt(),
                            chunk.text(),
                            hit.score(),
                            hit.vectorRank(),
                            hit.lexicalRank()));
                }
            }
            return new RetrieveResponse(chunks, repository.corpusWatermark());
        });
    }
}
