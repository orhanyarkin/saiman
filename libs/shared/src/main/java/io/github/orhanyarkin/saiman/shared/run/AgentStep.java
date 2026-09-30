package io.github.orhanyarkin.saiman.shared.run;

/** The fixed pipeline of one research run (ADR-0014). */
public enum AgentStep {
    PLANNER,
    RESEARCHER,
    RISK,
    SYNTHESIS
}
