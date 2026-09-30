package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.modelrouter.RouterProperties;
import io.github.orhanyarkin.saiman.sellerapi.llm.LlmRunProperties;
import io.github.orhanyarkin.saiman.sellerapi.retrieval.IngestProperties;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.x402.server.X402ServerProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The shipped timeouts (application.yaml, no test overrides) fit together and into the window. */
class ProductionTimeBudgetTests extends RagTestBase {

    @Autowired
    private RouterProperties routerProperties;

    @Autowired
    private IngestProperties ingestProperties;

    @Autowired
    private LlmRunProperties llm;

    @Autowired
    private X402ServerProperties x402;

    @Test
    void productionTimeoutsFitInsideTheDeadlineAndTheDeadlineInsideTheWindow() {
        assertThat(routerProperties.openai().timeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(routerProperties.openai().maxRetries()).isZero();
        assertThat(ingestProperties.readTimeout()).isLessThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(ingestProperties.retryAttempts()).isLessThanOrEqualTo(2);

        assertThat(routerProperties.openai().timeout()).isLessThan(llm.deadline());
        // Settle = connect + read timeout of the facilitator call, plus the 5 s margin.
        assertThat(x402.facilitator().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        Duration handlerPlusSettle = llm.deadline()
                .plus(x402.facilitator().connectTimeout())
                .plus(x402.facilitator().readTimeout())
                .plusSeconds(5);
        assertThat(handlerPlusSettle)
                .isLessThanOrEqualTo(Duration.ofSeconds(LlmRunProperties.MIN_AUTHORIZATION_WINDOW_SECONDS));
    }
}
