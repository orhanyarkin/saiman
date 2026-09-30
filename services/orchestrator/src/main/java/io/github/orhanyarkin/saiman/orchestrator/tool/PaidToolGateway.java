package io.github.orhanyarkin.saiman.orchestrator.tool;

import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalService;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalStatus;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalView;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalWaiter;
import io.github.orhanyarkin.saiman.orchestrator.budget.SpendProperties;
import io.github.orhanyarkin.saiman.orchestrator.events.RunEventAppender;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaidResponse;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentApprovalRequiredException;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentDeniedException;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentView;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentOutcomeUnknownException;
import io.github.orhanyarkin.saiman.orchestrator.payment.SellerCallFailedException;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only way a run calls a paid tool (ADR-0013, ADR-0014). In order, all in code:
 *
 * <ol>
 *   <li>count the call against {@code max-tool-calls-per-run} (invalid calls count too);
 *   <li>resolve the tool by name and validate the model's JSON arguments: exactly the tool's
 *       parameters, strings only, a ticker of the allowed shape, a cleaned 3..500 character question
 *       (unknown or extra fields, duplicates or other types: {@code INVALID_ARGS});
 *   <li>check the ticker against the seller's free catalogue (cached per run): unknown means no
 *       payment;
 *   <li>dedupe: an identical {@code (tool, argsHash)} call that already SETTLED in this run returns
 *       the stored result and pays nothing; one that is HELD (outcome unknown) is not sent again;
 *   <li>count paid calls against {@code max-paid-calls-per-run};
 *   <li>create the payment intent and send it through the tool's transport; above the approval
 *       threshold, wait for the human (the run is AWAITING_APPROVAL meanwhile) and re-send the same
 *       intent on APPROVE;
 *   <li>sanitise the result, store it for dedupe, record the evidence and emit the events.
 * </ol>
 *
 * Calls of one run are serialised by the session's lock. No exception ever reaches the caller:
 * every outcome is either a {@code <tool_data>} block or a fixed {@link ToolMessages} string.
 */
@Service
public class PaidToolGateway {

    private static final Logger LOG = LoggerFactory.getLogger(PaidToolGateway.class);
    private static final int MAX_ARGUMENTS_CHARS = 4_096;
    private static final String CALLS_METRIC = "saiman.tool.calls";

    private final ToolCatalog catalog;
    private final SellerTickerCatalog tickers;
    private final PaymentIntentService intents;
    private final ApprovalService approvals;
    private final ApprovalWaiter waiter;
    private final ToolResultSanitizer sanitizer;
    private final ToolResultStore store;
    private final RunEventAppender events;
    private final SpendProperties spend;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final ObjectReader argumentsReader;

    PaidToolGateway(
            ToolCatalog catalog,
            SellerTickerCatalog tickers,
            PaymentIntentService intents,
            ApprovalService approvals,
            ApprovalWaiter waiter,
            ToolResultSanitizer sanitizer,
            ToolResultStore store,
            RunEventAppender events,
            SpendProperties spend,
            ObservationRegistry observations,
            MeterRegistry meters,
            JsonMapper json) {
        this.catalog = catalog;
        this.tickers = tickers;
        this.intents = intents;
        this.approvals = approvals;
        this.waiter = waiter;
        this.sanitizer = sanitizer;
        this.store = store;
        this.events = events;
        this.spend = spend;
        this.observations = observations;
        this.meters = meters;
        // A repeated key ({"ticker":"X","ticker":"THYAO"}) is refused, not silently last-wins.
        this.argumentsReader = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    /** Opens the tool session of one run; the run package creates exactly one per run. */
    public RunToolSession openSession(UUID runId, RunPhaseListener phases) {
        return new RunToolSession(runId, this, events.emitterFor(runId), phases);
    }

    List<ToolDefinition> definitions() {
        return catalog.definitions();
    }

    Optional<Set<String>> fetchTickers() {
        return tickers.tickers();
    }

    String call(RunToolSession session, String toolName, String argumentsJson) {
        session.lock.lock();
        try {
            return callLocked(session, toolName, argumentsJson);
        } catch (RuntimeException e) {
            // Never the exception's message: it may carry seller or model text.
            LOG.warn(
                    "Tool call of run {} failed ({})",
                    session.runId(),
                    e.getClass().getSimpleName());
            count("unknown", "failed");
            return ToolMessages.TOOL_FAILED;
        } finally {
            session.lock.unlock();
        }
    }

    private String callLocked(RunToolSession session, String toolName, String argumentsJson) {
        session.toolCalls++;
        if (session.toolCalls > spend.maxToolCallsPerRun()) {
            count("unknown", "tool_call_limit");
            return ToolMessages.TOOL_CALL_LIMIT;
        }
        ResearchTool tool = catalog.find(toolName).orElse(null);
        if (tool == null) {
            denied(session, DenyReason.INVALID_ARGS, 0);
            count("unknown", "unknown_tool");
            return ToolMessages.UNKNOWN_TOOL;
        }
        String name = tool.name();
        ToolArguments arguments = parse(tool, argumentsJson).orElse(null);
        if (arguments == null) {
            denied(session, DenyReason.INVALID_ARGS, 0);
            count(name, "invalid_arguments");
            return ToolMessages.INVALID_ARGUMENTS;
        }
        String argsHash = arguments.hash(name);
        session.events()
                .emit(RunEventType.TOOL_CALL_REQUESTED, new RunEventData.ToolCallRequested(name, arguments.render()));

        Set<String> catalogue = session.knownTickers().orElse(null);
        if (catalogue == null) {
            completed(session, name, false, 0);
            count(name, "catalogue_unavailable");
            return ToolMessages.CATALOGUE_UNAVAILABLE;
        }
        if (!catalogue.contains(arguments.ticker())) {
            denied(session, DenyReason.INVALID_ARGS, 0);
            count(name, "unknown_ticker");
            return ToolMessages.UNKNOWN_TICKER;
        }

        Optional<PaymentIntentView> settled = intents.findSettled(session.runId(), name, argsHash);
        if (settled.isPresent()) {
            Optional<SanitizedToolResult> stored = store.find(settled.get().id());
            completed(
                    session, name, false, stored.map(r -> r.citations().size()).orElse(0));
            count(name, "deduplicated");
            return stored.map(sanitizer::render).orElse(ToolMessages.NO_STORED_RESULT);
        }
        if (store.hasHeld(session.runId(), name, argsHash)) {
            completed(session, name, false, 0);
            count(name, "held_duplicate");
            return ToolMessages.OUTCOME_UNKNOWN;
        }
        if (session.paidCalls >= spend.maxPaidCallsPerRun()) {
            denied(session, DenyReason.MAX_PAID_CALLS, 0);
            count(name, "paid_call_limit");
            return ToolMessages.denied(DenyReason.MAX_PAID_CALLS);
        }

        ToolInvocation invocation = new ToolInvocation(session.runId(), name, arguments, argsHash);
        return pay(session, tool.prepare(invocation), invocation);
    }

    /** One paid call, inside a {@code saiman.run.payment} observation (a child of the run's). */
    private String pay(RunToolSession session, ToolCall call, ToolInvocation invocation) {
        String tool = invocation.tool();
        Observation observation = Observation.createNotStarted("saiman.run.payment", observations)
                .lowCardinalityKeyValue("tool", tool)
                .start();
        String outcome = "failed";
        try (Observation.Scope scope = observation.openScope()) {
            PaidResponse response = sendWithApproval(session, call);
            if (response.paid()) {
                session.paidCalls++;
                session.events()
                        .emit(
                                RunEventType.PAYMENT_SETTLED,
                                new RunEventData.PaymentSettled(
                                        response.paymentIntentId(),
                                        response.amount(),
                                        Objects.requireNonNull(response.txHash())));
                observation.highCardinalityKeyValue(
                        "saiman.payment.amount_atomic",
                        Long.toString(response.amount().atomicUnits()));
            }
            outcome = response.paid() ? "settled" : "unpaid";
            Optional<SanitizedToolResult> result = sanitizer.sanitize(response.body());
            if (result.isEmpty()) {
                completed(session, tool, response.paid(), 0);
                outcome = outcome + "_unusable";
                return ToolMessages.UNUSABLE_RESULT;
            }
            if (response.paid()) {
                store.save(response.paymentIntentId(), invocation, result.get());
            }
            session.evidence().addAll(result.get().citations());
            completed(session, tool, response.paid(), result.get().citations().size());
            return sanitizer.render(result.get());
        } catch (PaymentDeniedException e) {
            outcome = "denied";
            denied(session, e.reason(), offeredAmount(e.paymentIntentId()));
            return ToolMessages.denied(e.reason());
        } catch (PaymentApprovalRequiredException e) {
            // A second approval request for an already-approved intent: refuse, never loop.
            outcome = "denied";
            call.abandon();
            denied(session, DenyReason.UNKNOWN_INTENT, offeredAmount(e.paymentIntentId()));
            return ToolMessages.denied(DenyReason.UNKNOWN_INTENT);
        } catch (PaymentOutcomeUnknownException e) {
            outcome = "ambiguous";
            session.paidCalls++;
            session.events()
                    .emit(
                            RunEventType.PAYMENT_AMBIGUOUS,
                            new RunEventData.PaymentAmbiguous(
                                    e.paymentIntentId(), Money.usdc(offeredAmount(e.paymentIntentId()))));
            completed(session, tool, false, 0);
            return ToolMessages.OUTCOME_UNKNOWN;
        } catch (SellerCallFailedException e) {
            if (e.paid()) {
                outcome = "settled_unusable";
                session.paidCalls++;
                PaymentIntentView view = intents.find(e.paymentIntentId()).orElse(null);
                if (view != null && view.txHash() != null) {
                    session.events()
                            .emit(
                                    RunEventType.PAYMENT_SETTLED,
                                    new RunEventData.PaymentSettled(
                                            view.id(), Money.usdc(amountOf(view)), view.txHash()));
                }
                completed(session, tool, true, 0);
                return ToolMessages.UNUSABLE_RESULT;
            }
            outcome = "seller_unavailable";
            completed(session, tool, false, 0);
            return ToolMessages.SELLER_UNAVAILABLE;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome);
            observation.stop();
            count(tool, outcome);
        }
    }

    /**
     * Sends the call; above the approval threshold, waits for the human and re-sends the same intent
     * on APPROVE. A REJECT or an expiry becomes a denial ({@code APPROVAL_REJECTED}/{@code
     * APPROVAL_EXPIRED}). An approval only opens the threshold gate: the guard re-checks the budget.
     */
    private PaidResponse sendWithApproval(RunToolSession session, ToolCall call) {
        try {
            return call.send();
        } catch (PaymentApprovalRequiredException required) {
            ApprovalStatus status = awaitApproval(session, required);
            if (status == ApprovalStatus.APPROVED) {
                return call.send();
            }
            throw new PaymentDeniedException(
                    required.paymentIntentId(),
                    status == ApprovalStatus.REJECTED ? DenyReason.APPROVAL_REJECTED : DenyReason.APPROVAL_EXPIRED);
        }
    }

    private ApprovalStatus awaitApproval(RunToolSession session, PaymentApprovalRequiredException required) {
        ApprovalView approval = approvals.find(required.approvalId()).orElseThrow();
        session.events()
                .emit(
                        RunEventType.PAYMENT_APPROVAL_REQUIRED,
                        new RunEventData.PaymentApprovalRequired(
                                approval.id(),
                                required.paymentIntentId(),
                                Money.usdc(approval.amountAtomic()),
                                approval.payTo(),
                                approval.resource(),
                                approval.expiresAt()));
        session.phases().awaitingApproval();
        ApprovalStatus status;
        try {
            status = waiter.await(approval.id());
        } finally {
            session.phases().resumed();
        }
        session.events()
                .emit(
                        RunEventType.PAYMENT_APPROVAL_DECIDED,
                        new RunEventData.PaymentApprovalDecided(approval.id(), status.name()));
        return status;
    }

    /**
     * The model's arguments as {@link ToolArguments}, or empty: must be a JSON object with exactly
     * the tool's parameters, each a string of the allowed shape.
     */
    private Optional<ToolArguments> parse(ResearchTool tool, String argumentsJson) {
        if (argumentsJson.length() > MAX_ARGUMENTS_CHARS) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = argumentsReader.readTree(argumentsJson);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        Set<String> expected = new HashSet<>();
        tool.parameters().forEach(p -> expected.add(p.jsonName()));
        Set<String> present = new HashSet<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (!field.getValue().isString()) {
                return Optional.empty();
            }
            present.add(field.getKey());
        }
        if (!present.equals(expected)) {
            return Optional.empty();
        }
        Optional<String> ticker =
                UntrustedText.ticker(node.get(ToolParameter.TICKER.jsonName()).asString());
        if (ticker.isEmpty()) {
            return Optional.empty();
        }
        String question = null;
        if (expected.contains(ToolParameter.QUESTION.jsonName())) {
            question = UntrustedText.question(
                            node.get(ToolParameter.QUESTION.jsonName()).asString())
                    .orElse(null);
            if (question == null) {
                return Optional.empty();
            }
        }
        return Optional.of(new ToolArguments(ticker.get(), question));
    }

    private void denied(RunToolSession session, DenyReason reason, long amountAtomic) {
        session.events()
                .emit(RunEventType.PAYMENT_DENIED, new RunEventData.PaymentDenied(reason, Money.usdc(amountAtomic)));
    }

    private void completed(RunToolSession session, String tool, boolean paid, int citations) {
        session.events()
                .emit(RunEventType.TOOL_CALL_COMPLETED, new RunEventData.ToolCallCompleted(tool, paid, citations));
    }

    /** The seller's offered amount as recorded on the intent, or 0 if the intent never saw an offer. */
    private long offeredAmount(UUID paymentIntentId) {
        return intents.find(paymentIntentId).map(PaidToolGateway::amountOf).orElse(0L);
    }

    private static long amountOf(PaymentIntentView view) {
        Long amount = view.amountAtomic();
        return amount == null ? 0 : amount;
    }

    private void count(String tool, String outcome) {
        meters.counter(CALLS_METRIC, "tool", catalog.names().contains(tool) ? tool : "unknown", "outcome", outcome)
                .increment();
    }
}
