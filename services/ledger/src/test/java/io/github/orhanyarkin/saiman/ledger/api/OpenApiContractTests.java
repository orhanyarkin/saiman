package io.github.orhanyarkin.saiman.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ledger.FakeChain;
import io.github.orhanyarkin.saiman.ledger.FakeSellerCreditNotes;
import io.github.orhanyarkin.saiman.ledger.RegistryProbe;
import io.github.orhanyarkin.saiman.ledger.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ledger's OpenAPI document equals the checked-in contract {@code docs/api/ledger.openapi.json} (ADR-0022), which
 * the dashboard generates its TypeScript client from. Normalised: keys sorted, {@code servers} removed (it carries the
 * random port), pretty-printed. After a deliberate API change, regenerate with
 * {@code SAIMAN_OPENAPI_UPDATE=1 ./gradlew :services:ledger:test --tests '*OpenApiContractTests*'} and commit the file.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "springdoc.api-docs.enabled=true")
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, RegistryProbe.class, FakeChain.class, FakeSellerCreditNotes.class})
class OpenApiContractTests {

    /** Relative to the Gradle project directory, the test JVM's working directory. */
    private static final Path CONTRACT = Path.of("../../docs/api/ledger.openapi.json");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private RestTestClient client;

    @Test
    void openApiDocumentMatchesTheCheckedInContract() throws IOException {
        String served = client.get()
                .uri("/v3/api-docs") // Host localhost:<port>, which the guard allows
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(served).isNotNull();
        String actual = normalise(served);

        if ("1".equals(System.getenv("SAIMAN_OPENAPI_UPDATE"))) {
            Files.createDirectories(CONTRACT.toAbsolutePath().getParent());
            Files.writeString(CONTRACT, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(Files.exists(CONTRACT))
                .as(
                        "%s is missing; generate it with SAIMAN_OPENAPI_UPDATE=1",
                        CONTRACT.toAbsolutePath().normalize())
                .isTrue();
        String expected = normalise(Files.readString(CONTRACT, StandardCharsets.UTF_8));
        assertThat(actual).as("""
                        The ledger API no longer matches %s. If the change is intended, regenerate it with \
                        SAIMAN_OPENAPI_UPDATE=1 ./gradlew :services:ledger:test --tests '*OpenApiContractTests*', \
                        commit it and run `pnpm gen:api` in web/.""", CONTRACT.normalize()).isEqualTo(expected);
    }

    /** Sorted keys, no {@code servers}, two-space pretty print and a final newline. */
    static String normalise(String json) {
        Object tree = sorted(JSON.readValue(json, Object.class));
        if (tree instanceof Map<?, ?> root) {
            root.remove("servers");
        }
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n";
    }

    private static Object sorted(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            map.forEach((k, v) -> out.put((String) k, sorted(v)));
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(sorted(v)));
            return out;
        }
        return node;
    }
}
