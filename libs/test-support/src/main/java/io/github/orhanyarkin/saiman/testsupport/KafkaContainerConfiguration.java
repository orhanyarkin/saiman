package io.github.orhanyarkin.saiman.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The JVM's shared Kafka container ({@link SharedContainers#kafka()}) as a bean: {@code @ServiceConnection} supplies
 * {@code spring.kafka.bootstrap-servers}. {@code destroyMethod = ""} keeps Spring from stopping it when one cached context closes while
 * others still use it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainerConfiguration {

    @Bean(destroyMethod = "")
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return SharedContainers.kafka();
    }
}
