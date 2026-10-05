package io.github.orhanyarkin.saiman.orchestrator.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The orchestrator's OpenAPI document (ADR-0022) against the committed snapshot {@code
 * docs/api/orchestrator.openapi.json}, which the web app's typed client is generated from. springdoc
 * is switched on for this test only. The document is normalised (sorted keys, {@code servers}
 * dropped, pretty-printed) so the comparison is stable.
 *
 * <p>When the API changed on purpose: {@code SAIMAN_OPENAPI_UPDATE=1 ./gradlew
 * :services:orchestrator:test --tests '*OpenApiContractTests'} rewrites the file; review and commit it
 * together with {@code pnpm gen:api}.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"springdoc.api-docs.enabled=true", "springdoc.writer-with-order-by-keys=true"})
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, OpenApiContractTests.DocsAccess.class})
class OpenApiContractTests {

    /**
     * The production chain denies {@code /v3/api-docs} (springdoc is off there). This test turns springdoc on, so it
     * adds a chain that opens exactly that path; the test-only chain is ordered first and matches nothing else.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class DocsAccess {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        SecurityFilterChain apiDocsOnly(HttpSecurity http) throws Exception {
            return http.securityMatcher("/v3/api-docs/**")
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable())
                    .build();
        }
    }

    private static final Path SNAPSHOT = Path.of("..", "..", "docs", "api", "orchestrator.openapi.json");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @org.springframework.beans.factory.annotation.Autowired
    private RestTestClient http;

    @Test
    void theDocumentMatchesTheCommittedSnapshot() throws IOException {
        String body = http.get()
                .uri("/v3/api-docs")
                .header("Host", "localhost")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        String actual = normalise(body);

        if ("1".equals(System.getenv("SAIMAN_OPENAPI_UPDATE"))) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, actual, StandardCharsets.UTF_8);
        }
        assertThat(SNAPSHOT)
                .as("docs/api/orchestrator.openapi.json is missing: run with SAIMAN_OPENAPI_UPDATE=1")
                .exists();
        String expected = Files.readString(SNAPSHOT, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            List<String> want = expected.lines().toList();
            List<String> got = actual.lines().toList();
            int line = 0;
            while (line < want.size() && line < got.size() && want.get(line).equals(got.get(line))) {
                line++;
            }
            throw new AssertionError("The OpenAPI document differs from docs/api/orchestrator.openapi.json (first"
                    + " difference at line " + (line + 1) + ":\n  snapshot: "
                    + (line < want.size() ? want.get(line) : "<end>") + "\n  actual:   "
                    + (line < got.size() ? got.get(line) : "<end>")
                    + "\nIf the API change is intended, rerun with SAIMAN_OPENAPI_UPDATE=1 and commit the file.");
        }
    }

    @Test
    void everyPropertyIsRequiredUnlessNullable() throws IOException {
        JsonNode schemas = MAPPER.readTree(normalise(http.get()
                        .uri("/v3/api-docs")
                        .header("Host", "localhost")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(String.class)
                        .returnResult()
                        .getResponseBody()))
                .get("components")
                .get("schemas");
        schemas.properties().forEach(entry -> {
            JsonNode schema = entry.getValue();
            JsonNode props = schema.get("properties");
            if (props == null) {
                return;
            }
            props.properties().forEach(prop -> {
                boolean nullable = prop.getValue().toString().contains("\"null\"");
                boolean required = schema.has("required")
                        && schema.get("required")
                                .valueStream()
                                .anyMatch(r -> r.asString().equals(prop.getKey()));
                assertThat(required || nullable)
                        .as(entry.getKey() + "." + prop.getKey() + " is neither required nor nullable")
                        .isTrue();
            });
        });
    }

    /** Sorted keys, no {@code servers} (they hold the random test port), pretty-printed, trailing newline. */
    static String normalise(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        Object sorted = sort(MAPPER.convertValue(root, Object.class));
        if (sorted instanceof Map<?, ?> map) {
            map.remove("servers");
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sorted) + "\n";
    }

    private static Object sort(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put((String) k, sort(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(OpenApiContractTests::sort).toList();
        }
        return value;
    }
}
