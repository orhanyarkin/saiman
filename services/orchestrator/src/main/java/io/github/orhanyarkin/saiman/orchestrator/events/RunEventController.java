package io.github.orhanyarkin.saiman.orchestrator.events;

import io.github.orhanyarkin.saiman.orchestrator.openapi.ProblemDetailSchema;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.util.List;
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
 *       comment every {@code heartbeat}; ends after the terminal event. A finished run is replayed
 *       from the database without a live subscription (204 if nothing is left after {@code
 *       Last-Event-ID}); a stream beyond the run's per-run limit replaces the run's oldest one; the
 *       global limit answers 429.
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

    @Operation(hidden = true) // documented once, on export(): one path and verb, two media types
    @GetMapping(path = "/api/v1/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(
            @PathVariable UUID runId, @RequestHeader(name = "Last-Event-ID", required = false) @Nullable String lastId)
            throws IOException {
        int lastEventId = parseLastEventId(lastId);
        requireRun(runId);
        if (isTerminal(runId)) {
            return replayFinished(runId, lastEventId);
        }
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        RunEventStream stream =
                new RunEventStream(runId, lastEventId, emitter, log, codec, this::isTerminal, properties.heartbeat());
        RunEventBus.Subscription subscription;
        try {
            // Subscribe before the stream's replay reads the database (see RunEventStream). A newer
            // stream of the same run may evict this one when the run's slots are full.
            subscription = bus.subscribe(runId, stream::offer, () -> {
                stream.close();
                emitter.complete();
            });
        } catch (RunEventBus.TooManyStreamsException e) {
            throw problem(HttpStatus.TOO_MANY_REQUESTS, "too many event streams");
        }
        // Every way a stream ends frees its slot at once (a disconnect surfaces as an error or as a
        // failed heartbeat write, which completes the emitter).
        emitter.onCompletion(() -> {
            stream.close();
            subscription.close();
        });
        emitter.onTimeout(() -> {
            stream.close();
            subscription.close();
        });
        emitter.onError(error -> {
            stream.close();
            subscription.close();
        });
        Thread.ofVirtual().name("run-events-" + runId).start(() -> {
            try {
                stream.run();
            } finally {
                subscription.close();
            }
        });
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    /**
     * A finished run needs no live stream and takes no slot: 204 if nothing is left after {@code
     * Last-Event-ID} (an {@code EventSource} stops reconnecting on 204), else the remaining events
     * from the database, then the stream completes.
     */
    private ResponseEntity<SseEmitter> replayFinished(UUID runId, int lastEventId) throws IOException {
        List<RunEvent> remaining = log.readAfter(runId, lastEventId);
        if (remaining.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        for (RunEvent event : remaining) {
            emitter.send(SseEmitter.event()
                    .id(Integer.toString(event.seq()))
                    .name(event.type().name())
                    .data(codec.encodeEnvelope(event)));
        }
        emitter.complete();
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    @Operation(
            operationId = "getRunEvents",
            summary = "A run's events: the complete JSON list, or a live SSE stream",
            description = "Content negotiation: Accept application/json returns the complete ordered event list;"
                    + " Accept text/event-stream streams the same envelopes (id = seq, event = type, resumes after"
                    + " Last-Event-ID, ends after the terminal event). The envelope and payload shapes are documented"
                    + " in docs/events/agent.run-step.v1.schema.json (and the fixtures next to it).")
    @Parameter(
            in = ParameterIn.HEADER,
            name = "Last-Event-ID",
            description = "SSE only: resume after this event seq",
            schema = @Schema(type = "string"))
    @ApiResponse(
            responseCode = "200",
            description = "The events",
            content = {
                @Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        array = @ArraySchema(schema = @Schema(type = "object"))),
                @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(type = "string"))
            })
    @ApiResponse(
            responseCode = "204",
            description = "SSE of a finished run: nothing left after Last-Event-ID",
            content = @Content)
    @ApiResponse(
            responseCode = "404",
            description = "Unknown run",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetailSchema.class)))
    @ApiResponse(
            responseCode = "429",
            description = "Too many event streams",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetailSchema.class)))
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
