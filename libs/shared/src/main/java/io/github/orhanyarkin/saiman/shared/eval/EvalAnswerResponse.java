package io.github.orhanyarkin.saiman.shared.eval;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** The answer service's result for one eval question; the cost is the router's USD micro-dollars (integers). */
public record EvalAnswerResponse(
        EvalOutcome outcome, @Nullable String answer, List<EvalCitation> citations, long modelCostUsdMicros) {}
