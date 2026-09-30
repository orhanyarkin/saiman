package io.github.orhanyarkin.saiman.orchestrator.tool;

import io.github.orhanyarkin.saiman.orchestrator.payment.PaidResourceClient;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaidResponse;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.SellerEndpoint;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The HTTP transport shared by the two seller tools: one {@link SellerEndpoint} (path template in
 * code, base URL from configuration) and the paying {@link PaidResourceClient}.
 */
abstract class SellerHttpTool implements ResearchTool {

    private final PaymentIntentService intents;
    private final PaidResourceClient client;
    private final SellerEndpoint endpoint;

    SellerHttpTool(PaymentIntentService intents, PaidResourceClient client, SellerEndpoint endpoint) {
        this.intents = intents;
        this.client = client;
        this.endpoint = endpoint;
    }

    /** The JSON request body for a POST endpoint, null for GET. */
    abstract @Nullable Object body(ToolArguments arguments);

    @Override
    public final ToolCall prepare(ToolInvocation invocation) {
        ToolArguments arguments = invocation.arguments();
        List<ToolParameter> parameters = parameters();
        if (parameters.contains(ToolParameter.QUESTION) != (arguments.question() != null)) {
            throw new IllegalArgumentException("arguments don't fit the tool");
        }
        PaymentIntentHandle handle = intents.create(
                invocation.runId(),
                name(),
                invocation.argsHash(),
                endpoint,
                Map.of(ToolParameter.TICKER.jsonName(), arguments.ticker()));
        return new HttpToolCall(handle, body(arguments));
    }

    private final class HttpToolCall implements ToolCall {
        private final PaymentIntentHandle handle;
        private final @Nullable Object body;

        HttpToolCall(PaymentIntentHandle handle, @Nullable Object body) {
            this.handle = handle;
            this.body = body;
        }

        @Override
        public UUID paymentIntentId() {
            return handle.id();
        }

        @Override
        public PaidResponse send() {
            return client.send(handle, body);
        }

        @Override
        public void abandon() {
            intents.closeUnsent(handle.id());
        }
    }
}
