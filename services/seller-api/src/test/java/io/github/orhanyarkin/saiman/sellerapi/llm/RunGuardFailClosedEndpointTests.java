package io.github.orhanyarkin.saiman.sellerapi.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** F1: when the guard can not decide (Redis down) the endpoint answers 503 without a model call. */
class RunGuardFailClosedEndpointTests extends RagTestBase {

    @MockitoSpyBean
    private UnsettledRunGuard guard;

    @Test
    void noGuardMeansNoModelCallNoSettlementAndAFixed503() {
        INGEST.retrieves(
                List.of(
                        FakeIngestServer.chunk("kap:5:0000", "THYAO", "one"),
                        FakeIngestServer.chunk("kap:5:0001", "THYAO", "two")),
                "v-fail-closed");
        router.replyWith("{\"answer\":\"Text.\",\"citedChunkIds\":[\"kap:5:0000\",\"kap:5:0001\"]}");
        doThrow(new RunGuardUnavailableException()).when(guard).tryStart(anyString());

        String body = postPaid("/v1/disclosures/THYAO/questions", "20000", "{\"question\":\"What happened?\"}")
                .expectStatus()
                .isEqualTo(503)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(router.routerRequests()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isZero();
        assertThat(body).doesNotContain("What happened");
    }
}
