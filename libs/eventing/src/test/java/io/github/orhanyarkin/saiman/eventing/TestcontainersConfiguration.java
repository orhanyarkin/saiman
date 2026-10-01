package io.github.orhanyarkin.saiman.eventing;

import io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * The shared Postgres (ADR-0020): one container per test JVM; each application context gets its own
 * database on the shared Postgres. Same images as deploy/compose.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresContainerConfiguration.class)
class TestcontainersConfiguration {}
