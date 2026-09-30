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
 * Decorator that reserves the worst-case cost against the daily cap before, and settles to the
 * reported usage after, every embedding call (same rules as {@link CostAdvisor}). All {@link
 * EmbeddingModel} entry points funnel into {@link #call(EmbeddingRequest)}, so batching callers (a
 * {@code VectorStore}) are covered too.
 */
final class CostGuardedEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(CostGuardedEmbeddingModel.class);
    private static final String TIER = "embedding";

    private final EmbeddingModel delegate;
    private final int dimensions;
    private final RouteCosting costing;
    private final CostGuard guard;
    private final RouterMetrics metrics;

    CostGuardedEmbeddingModel(
            EmbeddingModel delegate, int dimensions, RouteCosting costing, CostGuard guard, RouterMetrics metrics) {
        this.delegate = delegate;
        this.dimensions = dimensions;
        this.costing = costing;
        this.guard = guard;
        this.metrics = metrics;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        long chars = 0;
        for (String text : request.getInstructions()) {
            chars += text.length();
        }
        CostGuard.Reservation reservation;
        try {
            reservation = guard.reserve(costing.estimateEmbedding(chars));
        } catch (DailyCapExceededException e) {
            metrics.call(TIER, "cap");
            throw e;
        }
        EmbeddingResponse response;
        try {
            response = delegate.call(request);
        } catch (RuntimeException e) {
            if (e instanceof RequestNotSentException) {
                release(reservation);
            }
            metrics.call(TIER, "error");
            throw e;
        }
        settle(reservation, response);
        metrics.call(TIER, "ok");
        return response;
    }

    private void settle(CostGuard.Reservation reservation, EmbeddingResponse response) {
        Usage usage =
                response.getMetadata() == null ? null : response.getMetadata().getUsage();
        Integer tokens = usage == null ? null : usage.getPromptTokens();
        Money actual = null;
        if (tokens != null && tokens > 0) {
            try {
                actual = costing.actual(tokens, 0, response.getMetadata().getModel());
            } catch (RuntimeException e) {
                actual = null;
            }
        }
        try {
            if (actual == null) {
                log.warn("Embedding call reported no usable token usage; the worst-case estimate is charged");
                metrics.call(TIER, "no_usage");
                metrics.usage(TIER, 0, 0, reservation.estimate().atomicUnits());
                return;
            }
            guard.settle(reservation, actual);
            metrics.usage(TIER, tokens == null ? 0 : tokens, 0, actual.atomicUnits());
        } catch (RuntimeException e) {
            log.error("Accounting an embedding call failed: {}", e.getClass().getName());
        }
    }

    private void release(CostGuard.Reservation reservation) {
        try {
            guard.release(reservation);
        } catch (RuntimeException e) {
            log.error("Releasing a cost reservation failed: {}", e.getClass().getName());
        }
    }

    @Override
    public float[] embed(Document document) {
        String content = getEmbeddingContent(document);
        EmbeddingResponse response = call(new EmbeddingRequest(List.of(content == null ? "" : content), null));
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
