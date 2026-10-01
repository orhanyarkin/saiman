package io.github.orhanyarkin.saiman.evmrpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Known-answer test against the public Base Sepolia RPC (free, no key). Excluded from {@code check}; run with
 * {@code ./gradlew :libs:evm-rpc:testnetTest}. The transaction is the M1 live x402 payment of 0.01 USDC.
 */
@Tag("testnet")
class BaseSepoliaKnownAnswerTests {

    private static final String M1_TX = "0xe9811f2d8473aa1fd4c7bef4483f50adf1edf586812f3df6f9f63272a5e9d50a";

    private final BaseSepoliaUsdc chain = new JsonRpcBaseSepoliaUsdc(ChainProperties.of("https://sepolia.base.org"));

    @Test
    void m1TransactionHasAUsdcTransferAndAnAuthorizationUsed() {
        UsdcReceipt receipt = chain.receipt(M1_TX).orElseThrow();
        assertThat(receipt.succeeded()).isTrue();
        assertThat(receipt.transfers()).anyMatch(t -> t.value() == 10_000);
        assertThat(receipt.authorizationsUsed()).hasSize(1);

        String[] used = receipt.authorizationsUsed().getFirst().split(":", 2);
        ChainBlock safe = chain.block(BlockTag.SAFE);
        assertThat(safe.number()).isGreaterThanOrEqualTo(receipt.blockNumber());
        assertThat(chain.authorizationState(used[0], used[1], safe.number())).isTrue();
        assertThat(chain.authorizationState(used[0], "0x" + "00".repeat(32), safe.number()))
                .isFalse();
        assertThat(chain.findAuthorizationTx(used[0], used[1], receipt.blockNumber(), receipt.blockNumber()))
                .contains(M1_TX);
    }
}
