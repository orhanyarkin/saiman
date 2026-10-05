package io.github.orhanyarkin.saiman.sellerapi;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.SharedTestFacilitator;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Exposes every endpoint over HTTP, so only {@code management.endpoints.access.default: none}
 * keeps the sensitive ones away: this fails if that access setting is removed.
 */
// No Redis here, so its health indicator is off; the Redis nonce store is tested in DisclosureSummaryEndpointTests.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"management.endpoints.web.exposure.include=*", "management.health.redis.enabled=false"})
@AutoConfigureRestTestClient
class ActuatorAccessTests {

    @DynamicPropertySource
    static void x402Properties(DynamicPropertyRegistry registry) {
        SharedTestFacilitator.register(registry);
    }

    @Autowired
    private RestTestClient client;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/actuator/env",
                "/actuator/heapdump",
                "/actuator/beans",
                "/actuator/configprops",
                "/actuator/info"
            })
    void sensitiveEndpointsDoNotExistEvenWhenExposed(String uri) {
        client.get().uri(uri).exchange().expectStatus().isNotFound();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health"})
    void healthStaysAvailable(String uri) {
        client.get().uri(uri).exchange().expectStatus().isOk();
    }
}
