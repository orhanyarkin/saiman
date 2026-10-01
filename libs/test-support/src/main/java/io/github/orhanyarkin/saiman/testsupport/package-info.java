/**
 * Test-only container support (ADR-0020): one Postgres, one Kafka and one Redis per test JVM.
 *
 * <p>Spring tests import {@link io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration},
 * {@link io.github.orhanyarkin.saiman.testsupport.KafkaContainerConfiguration} or
 * {@link io.github.orhanyarkin.saiman.testsupport.RedisContainerConfiguration}; tests without a Spring context call
 * {@link io.github.orhanyarkin.saiman.testsupport.SharedContainers} directly. The images match deploy/compose.
 */
@NullMarked
package io.github.orhanyarkin.saiman.testsupport;

import org.jspecify.annotations.NullMarked;
