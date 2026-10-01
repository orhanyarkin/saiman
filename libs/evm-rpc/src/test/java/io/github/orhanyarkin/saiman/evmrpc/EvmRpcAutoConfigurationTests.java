package io.github.orhanyarkin.saiman.evmrpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.evmrpc.StubRpcServer.Reply;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EvmRpcAutoConfigurationTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(EvmRpcAutoConfiguration.class));

    @Test
    void noBeanWithoutAnRpcUrl() {
        runner.run(context -> assertThat(context).doesNotHaveBean(BaseSepoliaUsdc.class));
    }

    @Test
    void createsTheClientFromProperties() {
        try (StubRpcServer server = new StubRpcServer((m, p, n) -> Reply.result("\"0x14a34\""))) {
            runner.withPropertyValues("saiman.chain.rpc-url=" + server.url(), "saiman.chain.log-chunk-blocks=500")
                    .run(context -> {
                        assertThat(context).hasSingleBean(BaseSepoliaUsdc.class);
                        assertThat(context.getBean(ChainProperties.class).logChunkBlocks())
                                .isEqualTo(500);
                        assertThat(context.getBean(ChainProperties.class).maxRequestsPerSecond())
                                .isEqualTo(5);
                    });
        }
    }

    @Test
    void aBadUrlFailsStartup() {
        runner.withPropertyValues("saiman.chain.rpc-url=http://sepolia.base.org")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aUserBeanWins() {
        BaseSepoliaUsdc mine = new BaseSepoliaUsdc() {
            @Override
            public ChainBlock block(BlockTag tag) {
                return new ChainBlock(1, 1);
            }

            @Override
            public boolean authorizationState(String authorizer, String nonce, long blockNumber) {
                return false;
            }

            @Override
            public java.util.Optional<UsdcReceipt> receipt(String txHash) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<String> findAuthorizationTx(
                    String authorizer, String nonce, long fromBlock, long toBlock) {
                return java.util.Optional.empty();
            }
        };
        runner.withBean(BaseSepoliaUsdc.class, () -> mine)
                .withPropertyValues("saiman.chain.rpc-url=https://sepolia.base.org")
                .run(context ->
                        assertThat(context.getBean(BaseSepoliaUsdc.class)).isSameAs(mine));
    }
}
