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
 * Answers a buyer's question from retrieved KAP excerpts, with at least {@value #MIN_CITATIONS}
 * citations or not at all.
 *
 * <p>The router call uses {@link Tier#TIER1} with {@link DataClass#INTERNAL} because the prompt
 * carries a buyer question; the data class is fixed here in code. Every failure path throws before
 * a response exists, so the x402 starter never settles it.
 */
@Service
@ConditionalOnProperty(name = "seller.disclosures.source", havingValue = "rag")
class DisclosureAnswerService {

    static final int MIN_CITATIONS = 2;
    static final int TOP_K = 8;
    static final int EXCERPT_CHARS = 300;

    private final IngestClient ingest;
    private final GroundedGenerator generator;

    DisclosureAnswerService(IngestClient ingest, GroundedGenerator generator) {
        this.ingest = ingest;
        this.generator = generator;
    }

    DisclosureAnswerResponse answer(String ticker, String question, Deadline deadline) {
        boolean indexed = ingest.tickers().stream().anyMatch(t -> ticker.equals(t.ticker()));
        if (!indexed) {
            throw new TickerNotFoundException(ticker);
        }
        RetrieveResponse retrieved = ingest.retrieve(new RetrieveRequest(question, List.of(ticker), TOP_K));
        List<RetrievedChunk> excerpts = GroundedGenerator.usable(retrieved.chunks(), ticker);
        if (excerpts.size() < MIN_CITATIONS) {
            // Nothing to ground two citations in: refuse without spending a model call.
            throw new InsufficientCitationsException();
        }

        GroundedGenerator.Reply reply =
                generator.generate(Tier.TIER1, DataClass.INTERNAL, "answer", excerpts, "QUESTION", question, deadline);
        List<RetrievedChunk> cited = GroundedGenerator.validCitations(excerpts, reply.citedChunkIds());
        if (cited.size() < MIN_CITATIONS) {
            throw new InsufficientCitationsException();
        }
        List<DisclosureAnswerResponse.Citation> citations = cited.stream()
                .map(chunk -> new DisclosureAnswerResponse.Citation(
                        chunk.chunkId(),
                        chunk.sourceUrl(),
                        chunk.title(),
                        chunk.publishedAt(),
                        excerptOf(chunk.text())))
                .toList();
        return new DisclosureAnswerResponse(
                ticker,
                question,
                reply.text(),
                citations,
                RagDisclosureSummaryService.DATA_SOURCE,
                retrieved.corpusWatermark());
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
