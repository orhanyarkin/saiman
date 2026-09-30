package io.github.orhanyarkin.saiman.ingest.retrieval;

import io.github.orhanyarkin.saiman.shared.retrieval.IndexedTicker;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveRequest;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveResponse;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal retrieval API (ADR-0012). Reachable only on the compose network and 127.0.0.1;
 * it serves paid content, so it must never be routed publicly. Request validation lives in the
 * shared {@link RetrieveRequest} record: a violation fails deserialization and answers 400.
 */
@RestController
@RequestMapping("/internal/v1")
class RetrievalController {

    private static final Pattern CHUNK_ID = Pattern.compile("kap:\\d{1,10}:\\d{4}");

    private final HybridRetriever retriever;
    private final RetrievalRepository repository;

    RetrievalController(HybridRetriever retriever, RetrievalRepository repository) {
        this.retriever = retriever;
        this.repository = repository;
    }

    @PostMapping("/retrieve")
    RetrieveResponse retrieve(@RequestBody RetrieveRequest request) {
        return retriever.retrieve(request);
    }

    @GetMapping("/chunks/{chunkId}")
    RetrievedChunk chunk(@PathVariable String chunkId) {
        if (!CHUNK_ID.matcher(chunkId).matches()) {
            throw new InvalidChunkIdException();
        }
        return repository.find(chunkId).orElseThrow(ChunkNotFoundException::new);
    }

    @GetMapping("/tickers")
    List<IndexedTicker> tickers() {
        return repository.tickers();
    }

    /** Chunk id does not match {@code kap:<digits>:<4 digits>}; nothing is echoed back. */
    static final class InvalidChunkIdException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static final class ChunkNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
