package io.github.orhanyarkin.saiman.orchestrator.agent;

import io.github.orhanyarkin.saiman.modelrouter.Tier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.orchestrator.agents.*}.
 *
 * @param synthesisTier the tier of the final synthesis step: TIER2 per ADR-0014, TIER1 as the
 *     configured fallback while the TIER2 model id is unverified
 */
@ConfigurationProperties("saiman.orchestrator.agents")
public record AgentProperties(@DefaultValue("TIER2") Tier synthesisTier) {}
