package io.github.orhanyarkin.saiman.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/**
 * The JVM's shared Redis container ({@link SharedContainers#redis()}) as a bean: {@code @ServiceConnection} supplies
 * {@code spring.data.redis.*}. {@code destroyMethod = ""} keeps Spring from stopping it when one cached context closes while
 * others still use it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisContainerConfiguration {

    @Bean(destroyMethod = "")
    @ServiceConnection(name = "redis") // a bean's image is not known before it is created, so name the service
    GenericContainer<?> redisContainer() {
        return SharedContainers.redis();
    }
}
