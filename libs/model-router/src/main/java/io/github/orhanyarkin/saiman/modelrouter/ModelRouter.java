package io.github.orhanyarkin.saiman.modelrouter;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * The only way Saiman code obtains a chat or embedding model (CLAUDE.md rule 6, ADR-0011).
 * Implementations enforce the data-classification policy, the daily USD cap and record token/USD
 * metrics; callers never see a provider SDK.
 */
public interface ModelRouter {

    /**
     * A chat client for {@code tier}, with cost-cap and cost-metric advisors installed.
     *
     * @throws DataClassViolationException if the route for {@code tier} does not allow {@code dataClass}
     */
    ChatClient chatClient(Tier tier, DataClass dataClass);

    /**
     * The embedding model, wrapped with the cost cap and metrics. Its dimension is fixed by the
     * database schema (1536 for {@code text-embedding-3-small}).
     *
     * @throws DataClassViolationException if the embedding route does not allow {@code dataClass}
     */
    EmbeddingModel embeddingModel(DataClass dataClass);

    /**
     * Where today's global daily cap stands, so a caller can decide before starting work that needs model calls
     * (ADR-0026: the orchestrator answers a new public run with 503 and a replay offer instead). Fails <em>closed</em>:
     * an implementation that cannot read its counter reports the cap as used up rather than throwing or guessing.
     *
     * <p>The default throws, so a router implementation outside this module (a test double) is not forced to invent
     * a cap; {@link DefaultModelRouter} and the testing fakes implement it.
     *
     * @throws UnsupportedOperationException if this router has no daily cap to report
     */
    default DailyCapStatus dailyCap() {
        throw new UnsupportedOperationException("this router does not report a daily cap");
    }
}
