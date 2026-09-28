package io.github.orhanyarkin.x402.evm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.web3j.utils.Numeric;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Confirms this starter's EIP-712 domain construction against the real deployed test USDC
 * contract on Base Sepolia, via a read-only {@code eth_call} to {@code DOMAIN_SEPARATOR()}
 * (selector {@code 0x3644e515}, the first 4 bytes of {@code keccak256("DOMAIN_SEPARATOR()")}).
 *
 * <p>{@code @Tag("testnet")}: excluded from CI by default (build-logic's {@code
 * saiman.java-conventions} convention excludes this tag unless {@code -PincludeTestnet} is
 * passed). Read-only: {@code eth_call} makes no transaction and moves no funds (rule 1 in {@code
 * CLAUDE.md}).
 */
@Tag("testnet")
class Eip3009DomainSeparatorLiveTest {

    private static final String DOMAIN_SEPARATOR_SELECTOR = "0x3644e515";
    private static final String DEFAULT_RPC_URL = "https://sepolia.base.org";

    @Test
    void onChainDomainSeparatorMatchesWhatThisStarterComputes() throws Exception {
        // Field values don't matter for the domain separator (it only depends on the fixed
        // domain -- name/version/chainId/verifyingContract -- not the message), but
        // Eip3009Authorization requires a fully well-formed instance to construct at all.
        Eip3009Authorization sample = new Eip3009Authorization(
                "0x0000000000000000000000000000000000000001",
                "0x0000000000000000000000000000000000000002",
                "1",
                "0",
                "9999999999",
                Eip3009TypedData.randomNonce());
        byte[] expected = Eip3009TypedData.domainSeparator(sample);

        String rpcUrl = System.getenv().getOrDefault("BASE_SEPOLIA_RPC_URL", DEFAULT_RPC_URL);
        String requestBody = """
                {"jsonrpc":"2.0","method":"eth_call","params":[{"to":"%s","data":"%s"},"latest"],"id":1}
                """.formatted(TestnetAssets.USDC_ADDRESS, DOMAIN_SEPARATOR_SELECTOR);

        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(rpcUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode root = JsonMapper.builder().build().readTree(response.body());
        JsonNode result = root.path("result");
        assertThat(result.isMissingNode())
                .as("eth_call response has no result field: %s", response.body())
                .isFalse();

        byte[] onChainDomainSeparator = Numeric.hexStringToByteArray(result.asString());
        assertThat(onChainDomainSeparator).isEqualTo(expected);
    }
}
