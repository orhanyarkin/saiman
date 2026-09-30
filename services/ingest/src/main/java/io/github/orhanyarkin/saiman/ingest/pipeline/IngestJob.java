package io.github.orhanyarkin.saiman.ingest.pipeline;

import io.github.orhanyarkin.saiman.ingest.IngestProperties;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkCircuitOpenException;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkClient;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkCredentialException;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.BlockedDisclosure;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.Member;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One resumable ingest run over the configured tickers. A Postgres session-level advisory lock
 * (held on a dedicated, non-pooled connection for the whole run: closing that connection always
 * ends the session and so releases the lock, even if the explicit unlock fails) keeps two runs, in this or another process,
 * from working at the same time.
 */
@Component
public class IngestJob {

    private static final Logger log = LoggerFactory.getLogger(IngestJob.class);
    private static final long LOCK_KEY = 0x5A1A_0001_0000_0001L;

    private final MkkClient mkk;
    private final CompanyIngester companies;
    private final DocumentRepository documents;
    private final LockConnectionFactory lockConnections;
    private final IngestProperties properties;

    public IngestJob(
            MkkClient mkk,
            CompanyIngester companies,
            DocumentRepository documents,
            LockConnectionFactory lockConnections,
            IngestProperties properties) {
        this.mkk = mkk;
        this.companies = companies;
        this.documents = documents;
        this.lockConnections = lockConnections;
        this.properties = properties;
    }

    public RunReport run() {
        try (Connection lockConnection = lockConnections.open()) {
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

    /**
     * Whether another run currently holds the lock (a probe: it takes and releases the lock at
     * once). Used by the admin endpoint to answer 409 instead of starting a second run.
     */
    public boolean isRunning() {
        try (Connection probe = lockConnections.open()) {
            if (tryLock(probe)) {
                unlock(probe);
                return false;
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not probe the ingest advisory lock", e);
        }
    }

    private RunReport runLocked() {
        RunReport.Tally tally = new RunReport.Tally();
        List<String> unknown = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        boolean aborted = false;
        String abortReason = null;
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
                String symbol = ticker.strip().toUpperCase(Locale.ROOT);
                try {
                    if (companies.ingest(symbol, companyId, lastIndex, blocked, tally)) {
                        tally.tickersDone++;
                    }
                } catch (MkkCircuitOpenException | MkkCredentialException e) {
                    throw e; // not about this ticker: stop the run
                } catch (RuntimeException e) {
                    // One ticker's MKK/database failure must not stop the others. Its cursor stays where
                    // it was, so the next run resumes it. Messages: MKK ones carry status and code only.
                    log.warn(
                            "Ticker {} failed and is skipped for this run: {}",
                            symbol,
                            e instanceof MkkException
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName());
                    failed.add(symbol);
                }
            }
        } catch (MkkCircuitOpenException e) {
            log.warn("MKK circuit breaker is open; run aborted, it resumes from the cursors next time");
            aborted = true;
            abortReason = "circuit open";
        } catch (MkkCredentialException e) {
            log.error("MKK rejected the request; run aborted: {}", e.getMessage());
            aborted = true;
            abortReason = e.getMessage();
        }
        return new RunReport(false, aborted, abortReason, tally.outcomes, unknown, failed, tally.tickersDone);
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
