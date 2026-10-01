package io.github.orhanyarkin.saiman.evmrpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChainPropertiesTests {

    private static ChainProperties with(String url, List<String> hosts, int rps, int chunk) {
        return new ChainProperties(
                url, hosts, Duration.ofSeconds(1), Duration.ofSeconds(1), rps, chunk, 3, Duration.ofMillis(1), 1000);
    }

    @Test
    void acceptsTheAllowlistedHttpsHost() {
        assertThatCode(() -> ChainProperties.of("https://sepolia.base.org")).doesNotThrowAnyException();
        assertThat(ChainProperties.of("https://SEPOLIA.base.org/").allowedHosts())
                .containsExactly("sepolia.base.org");
    }

    @Test
    void acceptsLoopbackOverHttpForTests() {
        assertThatCode(() -> ChainProperties.of("http://127.0.0.1:8545")).doesNotThrowAnyException();
        assertThatCode(() -> ChainProperties.of("http://localhost:8545")).doesNotThrowAnyException();
    }

    @Test
    void rejectsPlainHttpToARemoteHost() {
        assertThatThrownBy(() -> ChainProperties.of("http://sepolia.base.org"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void rejectsAHostThatIsNotOnTheExactAllowlist() {
        assertThatThrownBy(() -> ChainProperties.of("https://evil.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-hosts");
        assertThatThrownBy(() -> ChainProperties.of("https://sepolia.base.org.evil.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChainProperties.of("https://api.sepolia.base.org"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUserInfo() {
        assertThatThrownBy(() -> ChainProperties.of("https://user:pw@sepolia.base.org"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user info")
                .hasMessageNotContaining("pw");
        assertThatThrownBy(() -> ChainProperties.of("http://user@127.0.0.1:8545"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIpLiteralsEvenWhenAllowlisted() {
        assertThatThrownBy(() -> with("https://203.0.113.7", List.of("203.0.113.7"), 5, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IP");
        assertThatThrownBy(() -> with("https://[2001:db8::1]", List.of("[2001:db8::1]"), 5, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankMalformedAndHostlessUrls() {
        assertThatThrownBy(() -> ChainProperties.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChainProperties.of("not a uri")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChainProperties.of("https:///path")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChainProperties.of("ftp://127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsTheChunkSizeAndRate() {
        List<String> hosts = List.of("sepolia.base.org");
        assertThatThrownBy(() -> with("https://sepolia.base.org", hosts, 5, 1001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("https://sepolia.base.org", hosts, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("https://sepolia.base.org", hosts, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> with("https://sepolia.base.org", hosts, 5, 1000)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAQueryOrFragment() {
        assertThatThrownBy(() -> ChainProperties.of("https://sepolia.base.org/?apikey=secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query or fragment")
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> ChainProperties.of("https://sepolia.base.org/#frag"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChainProperties.of("http://127.0.0.1:8545/?k=v"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
