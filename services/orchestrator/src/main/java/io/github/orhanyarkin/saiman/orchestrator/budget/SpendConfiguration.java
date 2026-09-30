package io.github.orhanyarkin.saiman.orchestrator.budget;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the spend-control and run-limit properties. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SpendProperties.class, RunLimitsProperties.class})
class SpendConfiguration {}
