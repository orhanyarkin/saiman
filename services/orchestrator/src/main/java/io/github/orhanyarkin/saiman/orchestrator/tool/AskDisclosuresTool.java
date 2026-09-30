package io.github.orhanyarkin.saiman.orchestrator.tool;

import io.github.orhanyarkin.saiman.orchestrator.payment.PaidResourceClient;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.SellerEndpoint;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code askDisclosures(ticker, question)}: the seller's paid {@code POST
 * /v1/disclosures/{ticker}/questions} with {@code {"question": ...}}.
 */
@Component
class AskDisclosuresTool extends SellerHttpTool {

    static final String NAME = "askDisclosures";

    AskDisclosuresTool(PaymentIntentService intents, PaidResourceClient client) {
        super(intents, client, SellerEndpoint.DISCLOSURE_QUESTION);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Paid: answers one question about a BIST company's public KAP disclosures, with citations.";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(ToolParameter.TICKER, ToolParameter.QUESTION);
    }

    @Override
    Object body(ToolArguments arguments) {
        return Map.of(ToolParameter.QUESTION.jsonName(), Objects.requireNonNull(arguments.question()));
    }
}
