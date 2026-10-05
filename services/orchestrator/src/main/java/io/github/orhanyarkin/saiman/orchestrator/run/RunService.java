package io.github.orhanyarkin.saiman.orchestrator.run;

import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalService;
import io.github.orhanyarkin.saiman.orchestrator.budget.RunLimitsProperties;
import io.github.orhanyarkin.saiman.orchestrator.budget.SpendProperties;
import io.github.orhanyarkin.saiman.orchestrator.events.RunEventAppender;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.tool.PaidToolGateway;
import io.github.orhanyarkin.saiman.orchestrator.tool.RunPhaseListener;
import io.github.orhanyarkin.saiman.orchestrator.tool.RunToolSession;
import io.github.orhanyarkin.saiman.orchestrator.tool.UntrustedText;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admits, executes and finishes research runs (ADR-0014).
 *
 * <ul>
 *   <li><b>Admission</b> (on the request thread): refused with 503 until the application is ready
 *       (Boot moves readiness to ACCEPTING_TRAFFIC only after every {@code ApplicationRunner},
 *       including the spend recovery, has finished); the question is cleaned and bounded; the budget
 *       is resolved once by {@link SpendProperties#resolveRunBudget}; a permit of a {@code
 *       Semaphore(max-concurrent)} is taken without waiting (429 otherwise, never an unbounded
 *       queue); the run is inserted QUEUED together with {@code RUN_STARTED}.
 *   <li><b>Execution</b> (on its own virtual thread): a root {@code saiman.run} observation (a new
 *       trace, whose id is stored on the run and returned by the API), RUNNING, the {@link
 *       ResearchPipeline}, AWAITING_APPROVAL while a payment waits for a human. A wall-clock
 *       deadline ({@code saiman.orchestrator.runs.deadline}) bounds the run: past it no tool call or
 *       step starts, an approval wait ends (the approval expires) and the run fails {@code
 *       RUN_DEADLINE}; spend stays bounded by the run, daily and LLM caps either way.
 *   <li><b>Finish</b> (one transaction): the run's PENDING approvals expire (with their intents) and
 *       its still-PENDING intents are released, the run becomes SUCCEEDED or FAILED and exactly one
 *       terminal event carries the persisted cost; the root span gets the same numbers.
 * </ul>
 */
@Service
public class RunService {

    static final String EVENTS_URL = "/api/v1/runs/%s/events";
    private static final Logger LOG = LoggerFactory.getLogger(RunService.class);
    private static final Duration TRACE_HANDOFF = Duration.ofSeconds(5);

    private final RunRepository runs;
    private final RunEventAppender events;
    private final PaidToolGateway gateway;
    private final ApprovalService approvals;
    private final PaymentIntentService intents;
    private final ObjectProvider<ResearchPipeline> pipeline;
    private final SpendProperties spend;
    private final RunLimitsProperties limits;
    private final ApplicationAvailability availability;
    private final ObservationRegistry observations;
    private final Tracer tracer;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;
    private final Semaphore permits;

    RunService(
            RunRepository runs,
            RunEventAppender events,
            PaidToolGateway gateway,
            ApprovalService approvals,
            PaymentIntentService intents,
            ObjectProvider<ResearchPipeline> pipeline,
            SpendProperties spend,
            RunLimitsProperties limits,
            ApplicationAvailability availability,
            ObservationRegistry observations,
            Tracer tracer,
            MeterRegistry meters,
            PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.events = events;
        this.gateway = gateway;
        this.approvals = approvals;
        this.intents = intents;
        this.pipeline = pipeline;
        this.spend = spend;
        this.limits = limits;
        this.availability = availability;
        this.observations = observations;
        this.tracer = tracer;
        this.meters = meters;
        this.tx = new TransactionTemplate(transactionManager);
        this.permits = new Semaphore(limits.maxConcurrent());
    }

    /** A run that was admitted and handed to its thread. */
    public record StartedRun(
            UUID runId, String eventsUrl, @Nullable String traceId) {}

    /**
     * Admits and starts a run.
     *
     * @param rawQuestion the user's question, untrusted
     * @param budgetAtomic the requested budget in USDC atomic units, or null for the default
     * @throws RunAdmissionException if the run is not admitted (nothing was stored then)
     */
    public StartedRun start(@Nullable String rawQuestion, @Nullable Long budgetAtomic) {
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            throw rejected(RunAdmissionException.Reason.NOT_READY);
        }
        String question =
                rawQuestion == null ? null : UntrustedText.question(rawQuestion).orElse(null);
        if (question == null) {
            throw rejected(RunAdmissionException.Reason.INVALID_QUESTION);
        }
        long budget;
        try {
            budget = spend.resolveRunBudget(budgetAtomic);
        } catch (IllegalArgumentException e) {
            throw rejected(RunAdmissionException.Reason.INVALID_BUDGET);
        }
        if (!permits.tryAcquire()) {
            throw rejected(RunAdmissionException.Reason.TOO_MANY_RUNS);
        }
        UUID runId = UUID.randomUUID();
        CompletableFuture<@Nullable String> traceHandoff = new CompletableFuture<>();
        try {
            tx.executeWithoutResult(status -> {
                runs.insertQueued(runId, question, budget, limits.llmBudgetUsdMicros());
                events.append(
                        runId, RunEventType.RUN_STARTED, new RunEventData.RunStarted(question, Money.usdc(budget)));
            });
            Thread.ofVirtual()
                    .name("research-run-" + runId)
                    .start(() -> execute(runId, question, budget, traceHandoff));
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
        meters.counter("saiman.runs.started").increment();
        return new StartedRun(runId, EVENTS_URL.formatted(runId), awaitTraceId(traceHandoff));
    }

    public Optional<RunSummary> summary(UUID runId) {
        return runs.summary(runId);
    }

    /** A newest-first page of runs; {@code limit} is already validated (1..100). */
    RunPage page(@Nullable RunCursor after, int limit) {
        List<RunListItem> rows = runs.page(after, limit);
        if (rows.size() <= limit) {
            return new RunPage(rows, null);
        }
        List<RunListItem> items = List.copyOf(rows.subList(0, limit));
        RunListItem last = items.getLast();
        return new RunPage(items, new RunCursor(last.createdAt(), last.runId()).encode());
    }

    public boolean exists(UUID runId) {
        return runs.exists(runId);
    }

    /** Runs executing now (for tests and metrics). */
    int activeRuns() {
        return limits.maxConcurrent() - permits.availablePermits();
    }

    private void execute(UUID runId, String question, long budget, CompletableFuture<@Nullable String> traceHandoff) {
        // A fresh virtual thread has no current observation or span: this is a new trace's root.
        Observation observation = Observation.createNotStarted("saiman.run", observations)
                .contextualName("research-run")
                .highCardinalityKeyValue("saiman.run.id", runId.toString())
                .start();
        RunOutcome outcome = RunOutcome.failed(FailureCode.INTERNAL_ERROR);
        try (Observation.Scope scope = observation.openScope()) {
            String traceId = currentTraceId();
            runs.markRunning(runId, traceId);
            traceHandoff.complete(traceId);
            Instant deadline = Instant.now().plus(limits.deadline());
            outcome = runPipeline(runId, question, budget, deadline);
            if (outcome instanceof RunOutcome.Failed && !Instant.now().isBefore(deadline)) {
                // Whatever failed last (an expired approval, no evidence), the cause is the deadline.
                outcome = RunOutcome.failed(FailureCode.RUN_DEADLINE);
            }
            RunCost cost = finish(runId, outcome);
            tagCost(observation, cost);
        } catch (RuntimeException e) {
            LOG.error("Run {} failed unexpectedly ({})", runId, e.getClass().getSimpleName());
            // The span records the exception type and status; the exporter's message is the class
            // name only (a fixed-text wrapper), since the original message may carry model text.
            observation.error(fixedTextError(e));
            outcome = RunOutcome.failed(FailureCode.INTERNAL_ERROR);
            try {
                tagCost(observation, finish(runId, outcome));
            } catch (RuntimeException second) {
                LOG.error(
                        "Run {} could not be finished ({})",
                        runId,
                        second.getClass().getSimpleName());
            }
        } finally {
            traceHandoff.complete(null); // no-op if already completed
            observation.lowCardinalityKeyValue(
                    "outcome", outcome instanceof RunOutcome.Succeeded ? "succeeded" : "failed");
            observation.lowCardinalityKeyValue(
                    "failure_code",
                    outcome instanceof RunOutcome.Failed failed ? failed.code().name() : "none");
            if (outcome instanceof RunOutcome.Failed failed) {
                // On the span only (high cardinality keys are not metric tags): the fixed code.
                observation.highCardinalityKeyValue(
                        "saiman.run.failure_code", failed.code().name());
            }
            observation.stop();
            permits.release();
        }
    }

    private RunOutcome runPipeline(UUID runId, String question, long budget, Instant deadline) {
        ResearchPipeline research = pipeline.getIfAvailable();
        if (research == null) {
            return RunOutcome.failed(FailureCode.PIPELINE_UNAVAILABLE);
        }
        RunToolSession tools = gateway.openSession(runId, phaseListener(runId, deadline));
        RunContext context = new RunContext(
                runId,
                question,
                Money.usdc(budget),
                events.emitterFor(runId),
                tools,
                runId.toString(),
                call -> recordModelCall(runId, call),
                deadline);
        try {
            return Objects.requireNonNull(research.execute(context), "pipeline returned no outcome");
        } catch (RuntimeException e) {
            // Never the message: it may carry model or seller text.
            LOG.warn("Pipeline of run {} threw {}", runId, e.getClass().getSimpleName());
            Observation root = observations.getCurrentObservation();
            if (root != null) {
                root.error(fixedTextError(e));
            }
            return RunOutcome.failed(FailureCode.INTERNAL_ERROR);
        }
    }

    /**
     * Ends the run in one transaction, in the usual lock order (approval -> payment_intent -> run):
     * stale approvals expire, unsent intents close, the run gets its terminal status and exactly one
     * terminal event.
     *
     * @return the persisted cost the terminal event carries
     */
    RunCost finish(UUID runId, RunOutcome outcome) {
        return Objects.requireNonNull(tx.execute(status -> {
            approvals.expirePendingForRun(runId);
            intents.closeUnsentForRun(runId);
            Optional<RunCost> cost = switch (outcome) {
                case RunOutcome.Succeeded succeeded ->
                    runs.finish(runId, RunStatus.SUCCEEDED, succeeded.report(), null)
                            .map(c -> {
                                events.append(
                                        runId,
                                        RunEventType.RUN_COMPLETED,
                                        new RunEventData.RunCompleted(succeeded.report(), c));
                                return c;
                            });
                case RunOutcome.Failed failed ->
                    runs.finish(runId, RunStatus.FAILED, null, failed.code()).map(c -> {
                        events.append(
                                runId,
                                RunEventType.RUN_FAILED,
                                new RunEventData.RunFailed(failed.code().name(), c));
                        return c;
                    });
            };
            // Already finished (e.g. by the startup recovery): report the stored numbers.
            return cost.orElseGet(() -> runs.summary(runId).orElseThrow().cost());
        }));
    }

    private void recordModelCall(UUID runId, RunEventData.ModelCallCompleted call) {
        Money cost = call.costUsd();
        if (!"USD".equals(cost.asset()) || cost.decimals() != Money.SIX_DECIMALS) {
            throw new IllegalArgumentException("model call cost must be 6-decimal USD");
        }
        tx.executeWithoutResult(status -> {
            runs.addLlmCost(runId, cost.atomicUnits());
            events.append(runId, RunEventType.MODEL_CALL_COMPLETED, call);
        });
    }

    private RunPhaseListener phaseListener(UUID runId, Instant deadline) {
        return new RunPhaseListener() {
            @Override
            public Instant deadline() {
                return deadline;
            }

            @Override
            public void awaitingApproval() {
                runs.changeActiveStatus(runId, RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL);
            }

            @Override
            public void resumed() {
                runs.changeActiveStatus(runId, RunStatus.AWAITING_APPROVAL, RunStatus.RUNNING);
            }
        };
    }

    /** An error for the span: the type's simple name only, never the message (it may hold model text). */
    private static Throwable fixedTextError(RuntimeException e) {
        IllegalStateException error = new IllegalStateException(e.getClass().getSimpleName());
        error.setStackTrace(new StackTraceElement[0]);
        return error;
    }

    /** The root span's cost attributes: exactly the persisted numbers the terminal event carries. */
    private static void tagCost(Observation observation, RunCost cost) {
        observation.highCardinalityKeyValue(
                "saiman.run.cost.payments_usdc_atomic",
                Long.toString(cost.paymentsUsdc().atomicUnits()));
        observation.highCardinalityKeyValue(
                "saiman.run.cost.llm_usd_micros", Long.toString(cost.llmUsd().atomicUnits()));
        observation.highCardinalityKeyValue(
                "saiman.run.cost.total_usd_micros",
                Long.toString(cost.totalUsd().atomicUnits()));
    }

    private @Nullable String currentTraceId() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        String traceId = span.context().traceId();
        return traceId.isBlank() || traceId.chars().allMatch(c -> c == '0') ? null : traceId;
    }

    private static @Nullable String awaitTraceId(CompletableFuture<@Nullable String> traceHandoff) {
        try {
            return traceHandoff.get(TRACE_HANDOFF.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
    }

    private RunAdmissionException rejected(RunAdmissionException.Reason reason) {
        meters.counter("saiman.runs.rejected", "reason", reason.name().toLowerCase(Locale.ROOT))
                .increment();
        return new RunAdmissionException(reason);
    }
}
