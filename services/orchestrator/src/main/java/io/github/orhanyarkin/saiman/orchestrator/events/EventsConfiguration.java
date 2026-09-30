package io.github.orhanyarkin.saiman.orchestrator.events;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the event-stream properties. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventStreamProperties.class)
class EventsConfiguration {}
