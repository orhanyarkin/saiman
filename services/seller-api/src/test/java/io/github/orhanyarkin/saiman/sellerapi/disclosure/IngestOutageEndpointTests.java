package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The ingest client under failure: retried on 5xx and timeouts, not retried on 4xx, always a 503
 * that is never settled. Own application context (own circuit breaker) on purpose, and only
 * failing calls: at most four failures are recorded here, below the breaker's five-call minimum,
 * so no test can open the circuit for another.
 */
@TestPropertySource(properties = {"seller.ingest.retry-attempts=2", "seller.ingest.read-timeout=300ms"})
class IngestOutageEndpointTests extends RagTestBase {

    @Test
    void serverErrorIsRetriedThenReturns503WithoutSettling() {
        INGEST.failWith(500);

        getPaid("/v1/disclosures/THYAO/summary", "10000")
                .expectStatus()
                .isEqualTo(503)
                .expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain(INGEST.url()));

        assertThat(INGEST.tickerCalls()).isEqualTo(2); // the ticker check fails first: no paid retrieval
        assertThat(INGEST.retrieveCalls()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isZero();
        assertThat(router.routerRequests()).isZero();
    }

    @Test
    void slowIngestTimesOutAndReturns503WithoutSettling() {
        INGEST.delayResponses(1_500);

        getPaid("/v1/disclosures/THYAO/summary", "10000").expectStatus().isEqualTo(503);

        assertThat(INGEST.tickerCalls()).isEqualTo(2); // the ticker check fails first: no paid retrieval
        assertThat(INGEST.retrieveCalls()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isZero();
    }

    @Test
    void clientErrorFromIngestIsNotRetriedAndStillReturns503() {
        INGEST.failWith(400);

        postPaid("/v1/disclosures/THYAO/questions", "20000", "{\"question\":\"What happened?\"}")
                .expectStatus()
                .isEqualTo(503);

        assertThat(INGEST.tickerCalls()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isZero();
        assertThat(router.routerRequests()).isZero();
    }
}
