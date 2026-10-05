package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import io.github.orhanyarkin.saiman.modelrouter.DataClass;
import io.github.orhanyarkin.saiman.modelrouter.Tier;
import io.github.orhanyarkin.saiman.sellerapi.llm.Deadline;
import io.github.orhanyarkin.saiman.sellerapi.llm.UnsettledRunGuard;
import io.github.orhanyarkin.saiman.sellerapi.retrieval.IngestClient;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveRequest;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveResponse;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Answers a question from retrieved KAP excerpts, with at least {@value #MIN_CITATIONS}
 * citations or not at all.
 *
 * <p>Two entry points share one core ({@link #ground}: the same retrieval, prompt, parser and
 * citation rebuild): the paid endpoint ({@link #answer}, run slot per payer) and the internal eval
 * API ({@link #groundForEval}, run slot per service caller, ADR-0025). Only the run slot and what
 * is done with the result differ.
 *
 * <p>The router call uses {@link Tier#TIER1} with {@link DataClass#INTERNAL} because the prompt
 * carries a question; the data class is fixed here in code. Excerpts are shown to the model with
 * their publication time, so date questions can be answered from metadata the chunk text rarely
 * repeats.
 */
@Service
@ConditionalOnProperty(name = "seller.disclosures.source", havingValue = "rag")
class DisclosureAnswerService {

    static final int MIN_CITATIONS = 2;
    static final int TOP_K = 8;
    static final int EXCERPT_CHARS = 300;

    /** The eval harness's service caller (ADR-0025), also its run-guard key. */
    static final String EVAL_CALLER = "evals";

    private final IngestClient ingest;
    private final GroundedGenerator generator;
    private final UnsettledRunGuard guard;
    private final EvalProperties evalLimits;

    DisclosureAnswerService(
            IngestClient ingest, GroundedGenerator generator, UnsettledRunGuard guard, EvalProperties evalLimits) {
        this.ingest = ingest;
        this.generator = generator;
        this.guard = guard;
        this.evalLimits = evalLimits;
    }

    /**
     * The model's answer and the retrieved chunks it validly cited (possibly fewer than {@value
     * #MIN_CITATIONS}: the caller decides what that means).
     */
    record Grounded(String text, List<RetrievedChunk> cited, Instant corpusWatermark) {

        boolean sufficientlyCited() {
            return cited.size() >= MIN_CITATIONS;
        }
    }

    /** The paid endpoint: one run slot per payer, and an answer with too few citations is a 422. */
    DisclosureAnswerResponse answer(String ticker, String question, Deadline deadline) {
        // The payer's run slot is taken before the first ingest call: a payer at their limit (or an
        // unavailable guard) must cost nothing, not an embedding per replayed authorization.
        return generator.withRunSlot(() -> {
            Grounded grounded = ground(ticker, question, deadline);
            if (!grounded.sufficientlyCited()) {
                throw new InsufficientCitationsException(true);
            }
            return toResponse(ticker, question, grounded);
        });
    }

    /**
     * The internal eval API: the same core under the {@value #EVAL_CALLER} caller's run slot
     * ({@code seller.eval.*} limits), with no payment of any kind. The result is returned even when
     * it is insufficiently cited.
     *
     * @throws io.github.orhanyarkin.saiman.sellerapi.llm.RunLimitExceededException if the caller's
     *     limits are reached (nothing ran)
     * @throws io.github.orhanyarkin.saiman.sellerapi.llm.RunGuardUnavailableException if the guard
     *     could not decide (fail closed)
     */
    Grounded groundForEval(String ticker, String question, Deadline deadline) {
        return withCallerSlot(() -> ground(ticker, question, deadline));
    }

    private <T> T withCallerSlot(Supplier<T> work) {
        guard.tryStartCaller(
                EVAL_CALLER, evalLimits.maxInFlight(), evalLimits.maxRunsPerHour(), evalLimits.maxRunsPerDay());
        try {
            return work.get();
        } finally {
            guard.finishCaller(EVAL_CALLER);
        }
    }

    /**
     * The shared core; the caller must hold a run slot.
     *
     * @throws TickerNotFoundException if the ticker is not indexed (no model call)
     * @throws InsufficientCitationsException with {@code modelRan() == false} if fewer than {@value
     *     #MIN_CITATIONS} usable excerpts were retrieved (no model call)
     */
    private Grounded ground(String ticker, String question, Deadline deadline) {
        // Every answer needs a model call: without time for one, refuse before retrieval spends an
        // embedding (e.g. an authorization that reached the handler close to its validBefore).
        generator.requireTimeForModel(deadline);
        boolean indexed = ingest.tickers().stream().anyMatch(t -> ticker.equals(t.ticker()));
        if (!indexed) {
            throw new TickerNotFoundException(ticker);
        }
        RetrieveResponse retrieved = ingest.retrieve(new RetrieveRequest(question, List.of(ticker), TOP_K));
        List<RetrievedChunk> excerpts = GroundedGenerator.usable(retrieved.chunks(), ticker);
        if (excerpts.size() < MIN_CITATIONS) {
            // Nothing to ground two citations in: refuse without spending a model call.
            throw new InsufficientCitationsException(false);
        }

        GroundedGenerator.Reply reply = generator.generate(
                Tier.TIER1, DataClass.INTERNAL, "answer", excerpts, "QUESTION", question, deadline, true);
        List<RetrievedChunk> cited = GroundedGenerator.validCitations(excerpts, reply.citedChunkIds());
        return new Grounded(reply.text(), cited, retrieved.corpusWatermark());
    }

    private static DisclosureAnswerResponse toResponse(String ticker, String question, Grounded grounded) {
        List<DisclosureAnswerResponse.Citation> citations = grounded.cited().stream()
                .map(chunk -> new DisclosureAnswerResponse.Citation(
                        chunk.chunkId(),
                        chunk.sourceUrl(),
                        GroundedGenerator.scrubLinks(chunk.title()),
                        chunk.publishedAt(),
                        GroundedGenerator.scrubLinks(excerptOf(chunk.text()))))
                .toList();
        return new DisclosureAnswerResponse(
                ticker,
                question,
                grounded.text(),
                citations,
                RagDisclosureSummaryService.DATA_SOURCE,
                grounded.corpusWatermark());
    }

    private static String excerptOf(String text) {
        String flat = text.strip();
        if (flat.length() <= EXCERPT_CHARS) {
            return flat;
        }
        int end = EXCERPT_CHARS;
        if (Character.isHighSurrogate(flat.charAt(end - 1))) {
            end--;
        }
        return flat.substring(0, end) + "…";
    }
}
