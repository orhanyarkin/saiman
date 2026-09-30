package io.github.orhanyarkin.saiman.modelrouter;

/**
 * Keys of the advisor params a caller sets on a {@code ChatClient} request to scope the model cost
 * of one unit of work (an agent run), for example {@code .advisors(a -> a.param(COST_SCOPE, runId))}.
 *
 * <p>Only trusted code may set them: they carry a limit, so model output, tool results and user
 * text must never become their value.
 */
public final class RouterAdvisorParams {

    /** The scope id (a run id); charges of every round trip of the call are summed under it. */
    public static final String COST_SCOPE = "saiman.router.cost-scope";

    /**
     * The scope's budget in USD micro-dollars ({@code Long}). Clamped to {@code
     * saiman.router.max-scope-budget-usd-micros}; the first reservation of a scope pins it.
     */
    public static final String COST_SCOPE_BUDGET_USD_MICROS = "saiman.router.cost-scope-budget-usd-micros";

    private RouterAdvisorParams() {}
}
