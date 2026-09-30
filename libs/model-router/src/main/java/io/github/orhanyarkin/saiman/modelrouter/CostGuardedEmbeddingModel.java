package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Decorator that applies the daily cap before and prices the usage after every embedding call. All
 * {@link EmbeddingModel} entry points funnel into {@link #call(EmbeddingRequest)}, so batching
 * callers (a {@code VectorStore}) are covered too.
 */
final class CostGuardedEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(CostGuardedEmbeddingModel.class);
    private static final String TIER = "embedding";

    private final EmbeddingModel delegate;
    private final int dimensions;
    private final RouterProperties.Price price;
    private final CostGuard guard;
    private final RouterMetrics metrics;

    CostGuardedEmbeddingModel(
            EmbeddingModel delegate,
            int dimensions,
            RouterProperties.Price price,
            CostGuard guard,
            RouterMetrics metrics) {
        this.delegate = delegate;
        this.dimensions = dimensions;
        this.price = price;
        this.guard = guard;
        this.metrics = metrics;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        try {
            guard.assertUnderCap();
        } catch (DailyCapExceededException e) {
            metrics.call(TIER, "cap");
            throw e;
        }
        EmbeddingResponse response;
        try {
            response = delegate.call(request);
        } catch (RuntimeException e) {
            metrics.call(TIER, "error");
            throw e;
        }
        Usage usage =
                response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage != null) {
            long in = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            long micros = 0;
            try {
                Money cost = CostCalculator.cost(price, in, 0);
                micros = cost.atomicUnits();
                guard.record(cost);
            } catch (RuntimeException ex) {
                log.error("Recording embedding cost failed: {}", ex.getClass().getName());
            }
            try {
                metrics.usage(TIER, in, 0, micros);
            } catch (RuntimeException ex) {
                log.error(
                        "Recording embedding metrics failed: {}", ex.getClass().getName());
            }
        }
        metrics.call(TIER, "ok");
        return response;
    }

    @Override
    public float[] embed(Document document) {
        EmbeddingResponse response = call(new EmbeddingRequest(List.of(getEmbeddingContent(document)), null));
        if (response.getResults().isEmpty()) {
            throw new IllegalStateException("embedding provider returned no vector");
        }
        return response.getResults().get(0).getOutput();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
