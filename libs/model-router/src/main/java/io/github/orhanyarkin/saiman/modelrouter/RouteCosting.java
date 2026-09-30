package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Cost arithmetic of one route: the worst-case estimate reserved before a call, and the actual cost
 * settled after it.
 *
 * <p>Estimate = {@code (ceil(chars / 2) * inputPrice + maxCompletionTokens * outputPrice) * (1 +
 * maxRetries)}. Characters, not tokens, are counted (two characters per token is a conservative
 * ratio for Turkish and English text); tool schemas and other request parts are not seen, which is
 * the estimate's error and the reason the provider-side limit stays the last resort.
 *
 * <p>Callers may override the model per request. The estimate and the settlement then use the
 * override's price (from the table, else the highest configured price), and never less than the
 * route's own price.
 */
final class RouteCosting {

    private static final Logger log = LoggerFactory.getLogger(RouteCosting.class);
    private static final long CHARS_PER_TOKEN = 2;
    /** OpenAI reports dated snapshots such as {@code gpt-5-mini-2025-08-07} for {@code gpt-5-mini}. */
    private static final Pattern SNAPSHOT_SUFFIX = Pattern.compile("-\\d[\\d-]*");

    private final String label;
    private final String routeModel;
    private final RouterProperties.Price routePrice;
    private final int maxCompletionTokens;
    private final int maxRetries;
    private final Map<String, RouterProperties.Price> prices;

    RouteCosting(
            String label,
            String routeModel,
            int maxCompletionTokens,
            int maxRetries,
            Map<String, RouterProperties.Price> prices) {
        this.label = label;
        this.routeModel = routeModel;
        this.maxCompletionTokens = maxCompletionTokens;
        this.maxRetries = maxRetries;
        this.prices = prices;
        RouterProperties.Price price = prices.get(routeModel);
        if (price == null) {
            throw new IllegalStateException("saiman.router.prices has no price for the model of " + label);
        }
        this.routePrice = price;
    }

    String label() {
        return label;
    }

    /** Worst case for a chat call. */
    Money estimate(Prompt prompt) {
        long chars = 0;
        for (Message message : prompt.getInstructions()) {
            String text = message.getText();
            chars += text == null ? 0 : text.length();
        }
        ChatOptions options = prompt.getOptions();
        long completion = maxCompletionTokens;
        RouterProperties.Price price = routePrice;
        if (options != null) {
            completion = Math.max(completion, requestedMaxTokens(options));
            String model = options.getModel();
            if (model != null && !matchesRoute(model)) {
                price = atLeastRoutePrice(priceOfOtherModel(model));
            }
        }
        return worstCase(price, chars, completion);
    }

    /** Worst case for an embedding call over {@code chars} characters of input. */
    Money estimateEmbedding(long chars) {
        return worstCase(routePrice, chars, 0);
    }

    /**
     * The actual cost of reported usage.
     *
     * @param reportedModel the model the provider says it used, if any
     * @throws IllegalArgumentException for negative token counts
     * @throws ArithmeticException on overflow
     */
    Money actual(long inputTokens, long outputTokens, @Nullable String reportedModel) {
        RouterProperties.Price price = routePrice;
        if (reportedModel != null && !reportedModel.isBlank() && !matchesRoute(reportedModel)) {
            price = atLeastRoutePrice(priceOfOtherModel(reportedModel));
            log.warn(
                    "Provider reported model {} on {}, not the routed model; priced at the highest applicable"
                            + " configured price",
                    reportedModel,
                    label);
        }
        return CostCalculator.cost(price, inputTokens, outputTokens);
    }

    private Money worstCase(RouterProperties.Price price, long chars, long completionTokens) {
        long inputTokens = Math.ceilDiv(chars, CHARS_PER_TOKEN);
        Money once = CostCalculator.cost(price, inputTokens, completionTokens);
        return Money.usdMicros(Math.multiplyExact(once.atomicUnits(), 1L + maxRetries));
    }

    private static long requestedMaxTokens(ChatOptions options) {
        long requested = 0;
        Integer maxTokens = options.getMaxTokens();
        if (maxTokens != null) {
            requested = maxTokens;
        }
        if (options instanceof OpenAiChatOptions openAi && openAi.getMaxCompletionTokens() != null) {
            requested = Math.max(requested, openAi.getMaxCompletionTokens());
        }
        return requested;
    }

    private boolean matchesRoute(String reportedModel) {
        return isSnapshotOf(reportedModel, routeModel);
    }

    static boolean isSnapshotOf(String reported, String base) {
        if (reported.equals(base)) {
            return true;
        }
        return reported.startsWith(base)
                && SNAPSHOT_SUFFIX.matcher(reported.substring(base.length())).matches();
    }

    /** The table price of {@code model} (or its base model), else the highest configured price. */
    private RouterProperties.Price priceOfOtherModel(String model) {
        RouterProperties.Price exact = prices.get(model);
        if (exact != null) {
            return exact;
        }
        RouterProperties.Price best = null;
        int bestLength = -1;
        for (Map.Entry<String, RouterProperties.Price> entry : prices.entrySet()) {
            if (entry.getKey().length() > bestLength && isSnapshotOf(model, entry.getKey())) {
                best = entry.getValue();
                bestLength = entry.getKey().length();
            }
        }
        return best != null ? best : highest();
    }

    private RouterProperties.Price highest() {
        long in = 0;
        long out = 0;
        for (RouterProperties.Price price : prices.values()) {
            in = Math.max(in, price.inputUsdMicrosPerMtok());
            out = Math.max(out, price.outputUsdMicrosPerMtok());
        }
        return new RouterProperties.Price(in, out);
    }

    private RouterProperties.Price atLeastRoutePrice(RouterProperties.Price price) {
        return new RouterProperties.Price(
                Math.max(price.inputUsdMicrosPerMtok(), routePrice.inputUsdMicrosPerMtok()),
                Math.max(price.outputUsdMicrosPerMtok(), routePrice.outputUsdMicrosPerMtok()));
    }
}
