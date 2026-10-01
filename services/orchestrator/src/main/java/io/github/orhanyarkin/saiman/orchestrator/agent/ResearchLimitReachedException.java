package io.github.orhanyarkin.saiman.orchestrator.agent;

/**
 * The researcher asked for more tool calls than a run allows. Thrown out of the tool callback to end
 * Spring AI's tool loop in code; the pipeline catches it and goes on with the evidence it has.
 */
public class ResearchLimitReachedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResearchLimitReachedException() {
        super("research tool-call limit reached");
    }
}
