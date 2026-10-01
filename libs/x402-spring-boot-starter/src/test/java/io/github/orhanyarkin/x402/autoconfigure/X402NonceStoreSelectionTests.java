package io.github.orhanyarkin.x402.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.testsupport.RedisContainerConfiguration;
import io.github.orhanyarkin.x402.server.PaymentNonceStore;
import io.github.orhanyarkin.x402.server.RedisPaymentNonceStore;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Blocking review finding: {@code @ConditionalOnBean(StringRedisTemplate.class)} on the Redis
 * nonce store was evaluated before Boot's own Redis auto-configuration had created that bean, so
 * {@link org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration} never won
 * the race and {@code InMemoryPaymentNonceStore} was chosen even with a real {@code
 * StringRedisTemplate} present. Fixed with {@code afterName} on {@link
 * X402ServerAutoConfiguration}; this test proves the fix end to end against a real Redis
 * container, wired the ordinary Boot way with {@code @ServiceConnection} through the shared {@link
 * RedisContainerConfiguration} ({@code spring-boot-data-redis} carries its own {@code
 * RedisContainerConnectionDetailsFactory}, in a package this starter does not otherwise depend on).
 */
@SpringBootTest(classes = X402NonceStoreSelectionTests.TestApplication.class)
@Import(RedisContainerConfiguration.class)
class X402NonceStoreSelectionTests {

    private static final FakeFacilitator FACILITATOR = new FakeFacilitator();

    @DynamicPropertySource
    static void x402Properties(DynamicPropertyRegistry registry) {
        registry.add("x402.server.facilitator.url", FACILITATOR::url);
    }

    @AfterAll
    static void stopFacilitator() {
        FACILITATOR.close();
    }

    @Autowired
    private PaymentNonceStore nonceStore;

    @Test
    void redisBackedStoreIsChosenWhenABootManagedStringRedisTemplateExists() {
        assertThat(nonceStore).isInstanceOf(RedisPaymentNonceStore.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {}
}
