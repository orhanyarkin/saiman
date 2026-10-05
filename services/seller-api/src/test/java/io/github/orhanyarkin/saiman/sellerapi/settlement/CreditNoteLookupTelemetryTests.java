package io.github.orhanyarkin.saiman.sellerapi.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RawHttp;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.SettlementTestBase;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Milestone audit L1: the HTTP server observation of a credit-note lookup (and so its trace span) never carries the
 * payment key, the payer or the nonce, in any key value or in its name, whatever the outcome.
 */
@Import(CreditNoteLookupTelemetryTests.Recorder.class)
class CreditNoteLookupTelemetryTests extends SettlementTestBase {

    private static final String PAYER = "0x" + "5a".repeat(20);
    private static final String NONCE = "0x" + "6b".repeat(32);
    private static final String KEY = "eip155:84532:0x036cbd53842c5426634e7929541ec2318f3dcf7e:" + PAYER + ":" + NONCE;

    @TestConfiguration(proxyBeanMethods = false)
    static class Recorder {
        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    /** Every stopped server-request observation, as text: name, contextual name and all key values. */
    static final class RecordingHandler implements ObservationHandler<ServerRequestObservationContext> {
        final List<String> stopped = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof ServerRequestObservationContext;
        }

        @Override
        public void onStop(ServerRequestObservationContext context) {
            StringBuilder text = new StringBuilder(context.getName() + " | " + context.getContextualName());
            for (KeyValue kv : context.getAllKeyValues()) {
                text.append(" | ").append(kv.getKey()).append('=').append(kv.getValue());
            }
            stopped.add(text.toString());
        }
    }

    @Autowired
    private RecordingHandler recorder;

    @LocalServerPort
    private int port;

    @Test
    void noObservationOfALookupCarriesThePaymentKey() throws IOException {
        recorder.stopped.clear();
        Map<String, String> ledger = Map.of("Authorization", TestTokens.bearer(TestTokens.SERVICE_LEDGER));
        Map<String, String> evals = Map.of("Authorization", TestTokens.bearer(TestTokens.SERVICE_EVALS));

        assertThat(get("/internal/credit-notes/" + KEY, ledger).status()).isEqualTo(404); // handled, no row
        assertThat(get("/internal/credit-notes/" + KEY, Map.of()).status()).isEqualTo(401);
        assertThat(get("/internal/credit-notes/" + KEY, evals).status()).isEqualTo(403);
        assertThat(get("/%69nternal/%63redit-notes/" + KEY, ledger).status()).isIn(400, 401, 404);
        assertThat(get("/internal/credit-notes/" + KEY + ";x=1", ledger).status())
                .isEqualTo(400);

        assertThat(recorder.stopped).hasSizeGreaterThanOrEqualTo(5);
        assertThat(recorder.stopped)
                .allSatisfy(text -> assertThat(text.toLowerCase(java.util.Locale.ROOT))
                        .doesNotContain(PAYER.substring(2))
                        .doesNotContain(NONCE.substring(2))
                        .doesNotContain("eip155:84532:0x036c"));
        assertThat(recorder.stopped)
                .anySatisfy(text -> assertThat(text).contains("/internal/credit-notes/{paymentKey}"));
    }

    @Test
    void otherPathsKeepTheDefaultKeyValues() throws IOException {
        recorder.stopped.clear();

        get("/v1/disclosures/THYAO/summary", Map.of());

        assertThat(recorder.stopped).anySatisfy(text -> assertThat(text).contains("/v1/disclosures/THYAO/summary"));
    }

    private RawHttp.Response get(String path, Map<String, String> headers) throws IOException {
        return RawHttp.exchange(port, "GET", path, "seller-api:8081", headers, null);
    }
}
