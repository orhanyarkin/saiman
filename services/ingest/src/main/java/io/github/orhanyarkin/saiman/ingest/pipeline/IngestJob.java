package io.github.orhanyarkin.saiman.ingest.pipeline;

import io.github.orhanyarkin.saiman.ingest.IngestProperties;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkCircuitOpenException;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkClient;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.BlockedDisclosure;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.Member;
import io.github.orhanyarkin.saiman.ingest.store.DocumentRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One resumable ingest run over the configured tickers. A Postgres session-level advisory lock
 * (held on a dedicated connection for the whole run) keeps two runs, in this or another process,
 * from working at the same time.
 */
@Component
public class IngestJob {

    private static final Logger log = LoggerFactory.getLogger(IngestJob.class);
    private static final long LOCK_KEY = 0x5A1A_0001_0000_0001L;

    private final MkkClient mkk;
    private final CompanyIngester companies;
    private final DocumentRepository documents;
    private final DataSource dataSource;
    private final IngestProperties properties;

    public IngestJob(
            MkkClient mkk,
            CompanyIngester companies,
            DocumentRepository documents,
            DataSource dataSource,
            IngestProperties properties) {
        this.mkk = mkk;
        this.companies = companies;
        this.documents = documents;
        this.dataSource = dataSource;
        this.properties = properties;
    }

    public RunReport run() {
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!tryLock(lockConnection)) {
                log.info("Another ingest run holds the lock; skipping");
                return RunReport.busy();
            }
            try {
                return runLocked();
            } finally {
                unlock(lockConnection);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not take the ingest advisory lock", e);
        }
    }

    private RunReport runLocked() {
        RunReport.Tally tally = new RunReport.Tally();
        List<String> unknown = new ArrayList<>();
        boolean aborted = false;
        try {
            long lastIndex = mkk.lastDisclosureIndex();
            Map<String, Long> directory = directory(mkk.members());
            Set<Long> blocked = purgeBlocked();
            for (String ticker : properties.tickers()) {
                Long companyId = directory.get(ticker.strip().toUpperCase(Locale.ROOT));
                if (companyId == null) {
                    log.warn("Ticker {} is not an MKK listed member; skipped", ticker);
                    unknown.add(ticker);
                    continue;
                }
                if (companies.ingest(ticker.strip().toUpperCase(Locale.ROOT), companyId, lastIndex, blocked, tally)) {
                    tally.tickersDone++;
                }
            }
        } catch (MkkCircuitOpenException e) {
            log.warn("MKK circuit breaker is open; run aborted, it resumes from the cursors next time");
            aborted = true;
        }
        return new RunReport(false, aborted, tally.outcomes, unknown, tally.tickersDone);
    }

    /** Ticker (any of a member's comma-separated stock codes) to MKK company id, listed companies only. */
    static Map<String, Long> directory(List<Member> members) {
        Map<String, Long> byTicker = new HashMap<>();
        for (Member member : members) {
            if (!"IGS".equals(member.memberType()) || member.stockCode() == null) {
                continue;
            }
            for (String code : member.stockCode().split(",", -1)) {
                String ticker = code.strip().toUpperCase(Locale.ROOT);
                if (!ticker.isEmpty()) {
                    byTicker.putIfAbsent(ticker, member.id());
                }
            }
        }
        return byTicker;
    }

    /** Deletes what KAP has blocked (personal-data removals) and returns the blocked disclosure indexes. */
    private Set<Long> purgeBlocked() {
        Set<Long> blocked = new HashSet<>();
        for (BlockedDisclosure entry : mkk.blockedDisclosures()) {
            // Attachments are never fetched; only a blocked disclosure itself matters.
            if (entry.blockedType() == null || "Disclosure".equalsIgnoreCase(entry.blockedType())) {
                blocked.add(entry.disclosureIndex());
                String id = DocumentRepository.documentId(entry.disclosureIndex());
                if (documents.find(id).isPresent()) {
                    documents.markBlocked(id);
                }
            }
        }
        return blocked;
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, LOCK_KEY);
            statement.execute();
        } catch (SQLException e) {
            log.warn("Could not release the ingest advisory lock explicitly; it ends with the connection");
        }
    }
}
