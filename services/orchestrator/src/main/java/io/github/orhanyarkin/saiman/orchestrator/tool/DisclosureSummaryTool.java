package io.github.orhanyarkin.saiman.orchestrator.tool;

import io.github.orhanyarkin.saiman.orchestrator.payment.PaidResourceClient;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.SellerEndpoint;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** {@code disclosureSummary(ticker)}: the seller's paid {@code GET /v1/disclosures/{ticker}/summary}. */
@Component
class DisclosureSummaryTool extends SellerHttpTool {

    static final String NAME = "disclosureSummary";

    DisclosureSummaryTool(PaymentIntentService intents, PaidResourceClient client) {
        super(intents, client, SellerEndpoint.DISCLOSURE_SUMMARY);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Paid: a short summary of a BIST company's recent public KAP disclosures, with citations.";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(ToolParameter.TICKER);
    }

    @Override
    @Nullable
    Object body(ToolArguments arguments) {
        return null;
    }
}
