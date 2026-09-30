package io.github.orhanyarkin.saiman.ingest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Exposes every endpoint over HTTP, so only {@code management.endpoints.access.default: none}
 * keeps the sensitive ones away: this fails if that access setting is removed.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.endpoints.web.exposure.include=*")
@AutoConfigureRestTestClient
@org.springframework.context.annotation.Import({TestcontainersConfiguration.class, TestModelRouterConfiguration.class})
class ActuatorAccessTests {

    @Autowired
    private RestTestClient client;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/heapdump", "/actuator/beans", "/actuator/configprops"})
    void sensitiveEndpointsDoNotExistEvenWhenExposed(String uri) {
        client.get().uri(uri).exchange().expectStatus().isNotFound();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/info"})
    void healthAndInfoStayAvailable(String uri) {
        client.get().uri(uri).exchange().expectStatus().isOk();
    }
}
