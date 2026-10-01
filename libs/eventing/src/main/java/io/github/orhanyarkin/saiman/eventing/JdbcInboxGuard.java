package io.github.orhanyarkin.saiman.eventing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InboxGuard} over {@link JdbcClient}. The insert joins the caller's transaction
 * ({@link Propagation#MANDATORY}), so the inbox row and the consumer's postings commit or roll back together;
 * a consumer that forgot its transaction fails loudly instead of deduping outside it.
 *
 * <p>The {@code inbox} table is unqualified: it lives in the connection's default schema, i.e. each service's own
 * schema (see the DDL in the package documentation).
 */
public class JdbcInboxGuard implements InboxGuard {

    private static final String INSERT =
            "INSERT INTO inbox (event_id, consumer, topic) VALUES (:eventId, :consumer, :topic) ON CONFLICT DO NOTHING";

    private final JdbcClient jdbc;

    public JdbcInboxGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(String eventId, String consumer, String topic) {
        int inserted = jdbc.sql(INSERT)
                .param("eventId", eventId)
                .param("consumer", consumer)
                .param("topic", topic)
                .update();
        return inserted == 1;
    }
}
