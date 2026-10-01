package io.github.orhanyarkin.saiman.orchestrator.outbox;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the Modulith registry ({@code event_publication}) for tests in any package. */
public final class OutboxTestAccess {

    /** One persisted publication: the event's simple class name and its serialized JSON. */
    public record Publication(String type, JsonNode event, boolean completed) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OutboxTestAccess() {}

    public static List<Publication> publications(JdbcClient jdbc) {
        return jdbc.sql("SELECT event_type, serialized_event, completion_date IS NOT NULL AS completed"
                        + " FROM event_publication ORDER BY publication_date, id")
                .query((rs, row) -> new Publication(
                        simpleName(rs.getString("event_type")),
                        JSON.readTree(rs.getString("serialized_event")),
                        rs.getBoolean("completed")))
                .list();
    }

    public static List<Publication> publications(JdbcClient jdbc, String simpleType) {
        return publications(jdbc).stream()
                .filter(p -> p.type().equals(simpleType))
                .toList();
    }

    /** The (intent, kind) rows of {@code payment_event_log}. */
    public static List<Map<String, Object>> eventLog(JdbcClient jdbc) {
        return jdbc.sql("SELECT payment_intent_id, kind, event_id FROM payment_event_log ORDER BY published_at")
                .query()
                .listOfRows();
    }

    private static String simpleName(String type) {
        return type.substring(type.lastIndexOf('.') + 1);
    }
}
