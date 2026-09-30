package io.github.orhanyarkin.saiman.orchestrator.events;

import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/runs/{runId}/events}, content-negotiated:
 *
 * <ul>
 *   <li>{@code text/event-stream}: the live SSE stream. {@code id} = seq, {@code event} = type,
 *       {@code data} = the envelope; resumes after {@code Last-Event-ID}; a {@code :heartbeat}
 *       comment every {@code heartbeat}; ends after the terminal event.
 *   <li>{@code application/json}: the complete ordered event list, the same envelopes and
 *       timestamps as the stream (the run export {@code make capture-demo} and the static replay
 *       demo use, ADR-0004).
 * </ul>
 *
 * Unknown runs get a 404 Problem Details response.
 */
@RestController
class RunEventController {

    private static final Pattern SEQ = Pattern.compile("\\d{1,9}");
    private static final String TERMINAL_STATUSES = "'SUCCEEDED', 'FAILED'";

    private final RunEventAppender log;
    private final RunEventCodec codec;
    private final RunEventBus bus;
    private final JdbcClient jdbc;
    private final EventStreamProperties properties;

    RunEventController(
            RunEventAppender log,
            RunEventCodec codec,
            RunEventBus bus,
            JdbcClient jdbc,
            EventStreamProperties properties) {
        this.log = log;
        this.codec = codec;
        this.bus = bus;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @GetMapping(path = "/api/v1/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
            @PathVariable UUID runId,
            @RequestHeader(name = "Last-Event-ID", required = false) @Nullable String lastId) {
        int lastEventId = parseLastEventId(lastId);
        requireRun(runId);
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        RunEventStream stream =
                new RunEventStream(runId, lastEventId, emitter, log, codec, this::isTerminal, properties.heartbeat());
        RunEventBus.Subscription subscription;
        try {
            // Subscribe before the stream's replay reads the database (see RunEventStream).
            subscription = bus.subscribe(runId, stream::offer);
        } catch (RunEventBus.TooManyStreamsException e) {
            throw problem(HttpStatus.TOO_MANY_REQUESTS, "too many event streams");
        }
        emitter.onCompletion(() -> {
            stream.close();
            subscription.close();
        });
        emitter.onTimeout(stream::close);
        emitter.onError(error -> stream.close());
        Thread.ofVirtual().name("run-events-" + runId).start(() -> {
            try {
                stream.run();
            } finally {
                subscription.close();
            }
        });
        return emitter;
    }

    @GetMapping(path = "/api/v1/runs/{runId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> export(@PathVariable UUID runId) {
        requireRun(runId);
        String body =
                log.readAfter(runId, 0).stream().map(codec::encodeEnvelope).collect(Collectors.joining(",", "[", "]"));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private boolean isTerminal(UUID runId) {
        return jdbc.sql("SELECT count(*) FROM run WHERE id = :id AND status IN (" + TERMINAL_STATUSES + ")")
                        .param("id", runId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    private void requireRun(UUID runId) {
        boolean exists = jdbc.sql("SELECT count(*) FROM run WHERE id = :id")
                        .param("id", runId)
                        .query(Integer.class)
                        .single()
                > 0;
        if (!exists) {
            throw problem(HttpStatus.NOT_FOUND, "run not found");
        }
    }

    private static int parseLastEventId(@Nullable String lastId) {
        if (lastId == null || lastId.isBlank()) {
            return 0;
        }
        if (!SEQ.matcher(lastId.strip()).matches()) {
            throw problem(HttpStatus.BAD_REQUEST, "Last-Event-ID must be a non-negative event seq");
        }
        return Integer.parseInt(lastId.strip());
    }

    private static ErrorResponseException problem(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }
}
