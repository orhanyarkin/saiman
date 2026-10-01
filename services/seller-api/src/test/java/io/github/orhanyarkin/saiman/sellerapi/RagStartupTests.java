package io.github.orhanyarkin.saiman.sellerapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.orhanyarkin.saiman.modelrouter.RouterProperties;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.StartupDatabase;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Startup rules of the RAG wiring: a RAG-mode seller without an ingest URL never starts, and the
 * compose secrets directory (a configtree) is what feeds the model router's API key.
 */
class RagStartupTests {

    private static final FakeFacilitator FACILITATOR = new FakeFacilitator();
    private static final String PAY_TO = "0x1111111111111111111111111111111111111111";

    @AfterAll
    static void stopFacilitator() {
        FACILITATOR.close();
    }

    private static SpringApplicationBuilder app() {
        return new SpringApplicationBuilder(SellerApiApplication.class).web(WebApplicationType.SERVLET);
    }

    @Test
    void ragModeWithoutAnIngestBaseUrlFailsStartup() {
        Throwable thrown = assertThrows(
                RuntimeException.class,
                () -> app().run(StartupDatabase.with(
                        "--server.port=0",
                        "--x402.server.facilitator.url=" + FACILITATOR.url(),
                        "--x402.server.pay-to=" + PAY_TO,
                        "--seller.disclosures.source=rag",
                        "--seller.ingest.base-url=")));

        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(IllegalStateException.class).hasMessageContaining("seller.ingest.base-url");
    }

    @Test
    void ragModeRejectsANonHttpIngestUrl() {
        Throwable thrown = assertThrows(
                RuntimeException.class,
                () -> app().run(StartupDatabase.with(
                        "--server.port=0",
                        "--x402.server.facilitator.url=" + FACILITATOR.url(),
                        "--x402.server.pay-to=" + PAY_TO,
                        "--seller.disclosures.source=rag",
                        "--seller.ingest.base-url=file:///etc/passwd")));

        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(IllegalStateException.class).hasMessageContaining("http(s)");
    }

    @Test
    void aDeadlineThatNoLongerFitsTheAuthorizationWindowFailsStartup() {
        // 26 s + the 3 s connect + 12 s read timeout of the facilitator + 5 s > the 45 s window.
        assertStartupFails("seller.llm.deadline", "--seller.llm.deadline=26s");
        // Same rule from the other side: a longer settle call eats into the handler's window.
        assertStartupFails("seller.llm.deadline", "--x402.server.facilitator.read-timeout=13s");
        assertStartupFails("seller.llm.deadline", "--x402.server.facilitator.connect-timeout=4s");
    }

    @Test
    void aModelTimeoutNotShorterThanTheDeadlineFailsStartupInRagMode() {
        assertStartupFails(
                "saiman.router.openai.timeout",
                "--seller.disclosures.source=rag",
                "--seller.ingest.base-url=http://127.0.0.1:9",
                "--seller.llm.deadline=10s",
                "--saiman.router.openai.timeout=10s",
                "--management.health.redis.enabled=false");
    }

    private static void assertStartupFails(String expectedMessagePart, String... extraArgs) {
        String[] args = new String[3 + extraArgs.length];
        args[0] = "--server.port=0";
        args[1] = "--x402.server.facilitator.url=" + FACILITATOR.url();
        args[2] = "--x402.server.pay-to=" + PAY_TO;
        System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);

        Throwable thrown = assertThrows(RuntimeException.class, () -> app().run(StartupDatabase.with(args)));

        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(IllegalStateException.class).hasMessageContaining(expectedMessagePart);
    }

    @Test
    void aConfigtreeFileNamedOpenaiApiKeyIsTheKeyTheRouterReads(@TempDir Path secrets) throws IOException {
        String marker = "sk-test-CONFIGTREE-MARKER";
        Files.writeString(secrets.resolve("openai_api_key"), marker);

        try (ConfigurableApplicationContext context = app().run(StartupDatabase.with(
                "--server.port=0",
                "--x402.server.facilitator.url=" + FACILITATOR.url(),
                "--x402.server.pay-to=" + PAY_TO,
                "--saiman.secrets-dir=" + secrets + "/",
                "--management.health.redis.enabled=false"))) {
            assertThat(context.getEnvironment().getProperty("openai_api_key")).isEqualTo(marker);
            RouterProperties router = context.getBean(RouterProperties.class);
            assertThat(router.openai().hasApiKey()).isTrue();
            assertThat(router.toString()).doesNotContain(marker);
        }
    }
}
