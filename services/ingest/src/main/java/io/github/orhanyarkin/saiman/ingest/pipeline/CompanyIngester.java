package io.github.orhanyarkin.saiman.ingest.pipeline;

import io.github.orhanyarkin.saiman.ingest.IngestProperties;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkClient;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.DisclosureSummary;
import io.github.orhanyarkin.saiman.ingest.store.CursorRepository;
import io.github.orhanyarkin.saiman.ingest.store.DocumentRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scans one company's disclosures with the windowed cursor (ADR-0010): the listing endpoint
 * examines a bounded, variable index window and returns only the matches, so a short or empty page
 * does not mean the end. A non-empty page moves the cursor to {@code max returned index + 1}; an
 * empty page moves it by a fixed step; the scan ends when the cursor passes {@code
 * lastDisclosureIndex}. The cursor is persisted after every page, so a run resumes where it stopped.
 */
@Component
public class CompanyIngester {

    /** First index of the queryable range of the MKK test environment (ADR-0010). */
    public static final long FIRST_INDEX = 1_091_689L;

    private static final Logger log = LoggerFactory.getLogger(CompanyIngester.class);

    private final MkkClient mkk;
    private final DocumentIndexer indexer;
    private final CursorRepository cursors;
    private final DocumentRepository documents;
    private final IngestProperties properties;

    public CompanyIngester(
            MkkClient mkk,
            DocumentIndexer indexer,
            CursorRepository cursors,
            DocumentRepository documents,
            IngestProperties properties) {
        this.mkk = mkk;
        this.indexer = indexer;
        this.cursors = cursors;
        this.documents = documents;
        this.properties = properties;
    }

    /** @return false if the ticker was already fully scanned */
    boolean ingest(String ticker, long companyId, long lastIndex, Set<Long> blocked, RunReport.Tally tally) {
        var saved = cursors.find(ticker);
        if (saved.isPresent() && saved.get().done()) {
            return false;
        }
        long position = saved.map(CursorRepository.Cursor::index).orElse(FIRST_INDEX);
        long step = Math.max(1, properties.mkk().emptyPageStep());
        while (position <= lastIndex) {
            List<DisclosureSummary> page = mkk.disclosures(position, companyId);
            if (page.isEmpty()) {
                position += step;
            } else {
                long max = page.stream()
                        .mapToLong(DisclosureSummary::disclosureIndex)
                        .max()
                        .orElse(position);
                page.stream()
                        .filter(s -> s.companyId() == companyId)
                        .filter(s -> s.disclosureClass() != null
                                && properties.classes().contains(s.disclosureClass()))
                        .sorted(Comparator.comparingLong(DisclosureSummary::disclosureIndex))
                        .forEach(s -> tally.add(handle(ticker, s, blocked)));
                position = Math.max(max + 1, position + 1);
            }
            cursors.save(ticker, position, false);
        }
        cursors.save(ticker, position, true);
        log.info("Ticker {} scanned up to index {}", ticker, lastIndex);
        return true;
    }

    private Outcome handle(String ticker, DisclosureSummary summary, Set<Long> blocked) {
        if (blocked.contains(summary.disclosureIndex())) {
            String id = DocumentRepository.documentId(summary.disclosureIndex());
            if (documents.find(id).isPresent()) {
                documents.markBlocked(id);
            }
            return Outcome.BLOCKED;
        }
        return indexer.process(ticker, summary);
    }

    /** Rolls a ticker's cursor back so a rescan reaches {@code fromIndex} again (used by the DLQ retry). */
    public void rewind(String ticker, long fromIndex) {
        long position = cursors.find(ticker)
                .map(c -> c.done() ? Long.MAX_VALUE : c.index())
                .orElse(FIRST_INDEX);
        cursors.save(ticker, Math.min(position, fromIndex), false);
    }
}
