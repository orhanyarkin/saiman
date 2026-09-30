package io.github.orhanyarkin.saiman.modelrouter;

/**
 * Router meters. Tag values are tier names and outcomes only, never prompt text or model output.
 */
public interface RouterMetrics {

    /** Counts a call; {@code outcome} is {@code ok}, {@code error} or {@code cap}. */
    void call(String tier, String outcome);

    /** Records token usage and the computed cost of one finished call. */
    void usage(String tier, long inputTokens, long outputTokens, long costUsdMicros);

    RouterMetrics NOOP = new RouterMetrics() {
        @Override
        public void call(String tier, String outcome) {}

        @Override
        public void usage(String tier, long inputTokens, long outputTokens, long costUsdMicros) {}
    };
}
