package io.github.orhanyarkin.saiman.sellerapi.testsupport;

import io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * The shared Postgres (ADR-0020): one container per test JVM; each application context gets its own database on it.
 * Import it into every {@code @SpringBootTest}: the application has a database. Same images as deploy/compose.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresContainerConfiguration.class)
public class TestcontainersConfiguration {}
