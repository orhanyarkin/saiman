package io.github.orhanyarkin.saiman.ingest.mkk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.ingest.IngestProperties;
import io.github.orhanyarkin.saiman.ingest.mkk.FakeMkkServer.Reply;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class MkkClientTests {

    private static FakeMkkServer server;

    @BeforeAll
    static void start() {
        server = FakeMkkServer.start(SyntheticKap.CREDENTIALS);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @BeforeEach
    void load() {
        server.reset();
        SyntheticKap.load(server);
    }

    private static MkkClient client(String credentials, int attempts) {
        IngestProperties.Mkk config = new IngestProperties.Mkk(
                server.baseUrl(),
                credentials,
                600_000,
                2000,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                attempts,
                Duration.ofMillis(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30));
        RestClient rest = MkkClient.configure(RestClient.builder(), config, "test")
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults()
                                .withRedirects(HttpRedirects.DONT_FOLLOW)
                                .withConnectTimeout(config.connectTimeout())
                                .withReadTimeout(config.readTimeout())))
                .build();
        return new MkkClient(rest, config, null);
    }

    @Test
    void readsTheFiveEndpointsAndSendsAUserAgent() {
        MkkClient client = client(SyntheticKap.CREDENTIALS, 4);

        assertThat(client.lastDisclosureIndex()).isEqualTo(1_105_000L);
        assertThat(client.members()).hasSize(3);
        assertThat(client.disclosures(1_091_689L, SyntheticKap.THYAO))
                .extracting(MkkDtos.DisclosureSummary::disclosureIndex)
                .containsExactly(1_093_000L, 1_093_500L, 1_094_000L);
        assertThat(client.disclosureDetail(1_093_000L).htmlMessages()).hasSize(1);
        assertThat(client.blockedDisclosures()).hasSize(2);
        assertThat(server.userAgents())
                .allSatisfy(
                        ua -> assertThat(ua).startsWith("saiman-ingest/test (+https://github.com/orhanyarkin/saiman"));
    }

    @Test
    void windowedListingCanBeEmptyWithoutBeingTheEnd() {
        MkkClient client = client(SyntheticKap.CREDENTIALS, 4);

        assertThat(client.disclosures(1_103_500L, SyntheticKap.THYAO)).isEmpty();
        assertThat(client.disclosures(1_101_000L, SyntheticKap.THYAO)).hasSize(2);
    }

    @Test
    void retriesA500ThenSucceeds() {
        server.enqueue("/lastDisclosureIndex", Reply.status(500));
        server.enqueue("/lastDisclosureIndex", Reply.status(503));

        assertThat(client(SyntheticKap.CREDENTIALS, 4).lastDisclosureIndex()).isEqualTo(1_105_000L);
        assertThat(server.countRequests("/lastDisclosureIndex")).isEqualTo(3);
    }

    @Test
    void honoursRetryAfterOn429() {
        server.enqueue("/members", Reply.retryAfter(429, 1));
        long started = System.nanoTime();

        List<MkkDtos.Member> members = client(SyntheticKap.CREDENTIALS, 4).members();

        assertThat(members).isNotEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
        assertThat(server.countRequests("/members")).isEqualTo(2);
    }

    @Test
    void otherClientErrorsAreNeverRetried() {
        server.always("/members", Reply.status(404));

        assertThatThrownBy(() -> client(SyntheticKap.CREDENTIALS, 4).members())
                .isInstanceOfSatisfying(
                        MkkHttpException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThat(server.countRequests("/members")).isEqualTo(1);
    }

    @Test
    void givesUpAfterTheConfiguredAttempts() {
        server.always("/members", Reply.status(500));

        assertThatThrownBy(() -> client(SyntheticKap.CREDENTIALS, 3).members())
                .isInstanceOfSatisfying(
                        MkkHttpException.class, e -> assertThat(e.status()).isEqualTo(500));
        assertThat(server.countRequests("/members")).isEqualTo(3);
    }

    @Test
    void redirectsAreNotFollowed() {
        server.always(
                "/members",
                new Reply(302, "{}", java.util.Map.of("Location", server.baseUrl() + "/blockedDisclosures")));

        assertThatThrownBy(() -> client(SyntheticKap.CREDENTIALS, 4).members()).isInstanceOf(MkkException.class);
        assertThat(server.countRequests("/blockedDisclosures")).isZero();
    }

    @Test
    void theCredentialNeverAppearsInExceptionsPropertiesOrLogs(CapturedOutput output) {
        String marker = "PLANTED-CREDENTIAL-MARKER-9f3a";
        String planted = marker;
        server.always("/members", Reply.status(500));
        MkkClient wrong = client(planted, 2); // the fake answers 401 for a wrong credential

        Throwable unauthorized = org.assertj.core.api.Assertions.catchThrowable(wrong::lastDisclosureIndex);
        Throwable serverError = org.assertj.core.api.Assertions.catchThrowable(
                () -> client(SyntheticKap.CREDENTIALS, 2).members());
        String properties = new IngestProperties.Mkk(
                        "http://x",
                        planted,
                        5,
                        5000,
                        Duration.ZERO,
                        Duration.ZERO,
                        1,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO)
                .toString();

        assertThat(unauthorized).isInstanceOf(MkkHttpException.class).hasMessage("MKK request failed with status 401");
        assertThat(serverError).hasMessage("MKK request failed with status 500");
        for (Throwable t : List.of(unauthorized, serverError)) {
            assertThat(t.toString()).doesNotContain(marker).doesNotContain(SyntheticKap.CREDENTIALS);
            assertThat(t.getCause()).isNull();
        }
        assertThat(properties).doesNotContain(marker).contains("<redacted>");
        assertThat(output.getAll()).doesNotContain(marker).doesNotContain(SyntheticKap.CREDENTIALS);
        assertThat(Set.copyOf(server.requests()).toString()).doesNotContain(marker);
    }
}
