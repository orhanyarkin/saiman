package io.github.orhanyarkin.saiman.ingest.dlq;

import io.github.orhanyarkin.saiman.ingest.pipeline.CompanyIngester;
import io.github.orhanyarkin.saiman.ingest.store.DocumentRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-opens parked documents. A retry resets each {@code FAILED} document to {@code PENDING} with a
 * fresh attempt budget, clears the dead-letter rows and rewinds its ticker's cursor to the
 * document, so the next (resumable) run reaches it again; terminal documents on the way are
 * skipped without any call.
 */
@Service
public class DeadLetterService {

    private final DocumentRepository documents;
    private final DeadLetterRepository deadLetters;
    private final CompanyIngester companies;

    public DeadLetterService(
            DocumentRepository documents, DeadLetterRepository deadLetters, CompanyIngester companies) {
        this.documents = documents;
        this.deadLetters = deadLetters;
        this.companies = companies;
    }

    /** @return how many parked documents were re-opened */
    @Transactional
    public int retry() {
        List<String> ids = documents.resetFailed();
        for (String id : ids) {
            long index = Long.parseLong(id.substring(id.indexOf(':') + 1));
            documents.tickerOf(id).ifPresent(ticker -> companies.rewind(ticker, index));
            deadLetters.clear(Long.toString(index));
        }
        return ids.size();
    }
}
