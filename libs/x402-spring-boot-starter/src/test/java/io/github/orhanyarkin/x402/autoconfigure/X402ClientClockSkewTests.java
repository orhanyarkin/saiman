package io.github.orhanyarkin.x402.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.client.X402PaymentInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

/** {@code x402.client.clock-skew-seconds}: default, bounds and wiring into the interceptor. */
class X402ClientClockSkewTests {

    private static final String COW_PRIVATE_KEY = "0xc85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4";
    private static final String PAY_TO = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(X402ClientAutoConfiguration.class))
            .withPropertyValues(
                    "x402.client.private-key=" + COW_PRIVATE_KEY,
                    "x402.client.max-amount-per-request=1000",
                    "x402.client.allowed-pay-to=" + PAY_TO);

    @Test
    void defaultsTo600() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(context.getBean(X402PaymentInterceptor.class), "clockSkewSeconds"))
                    .isEqualTo(600L);
        });
    }

    @Test
    void configuredValuesReachTheInterceptor() {
        for (String value : new String[] {"30", "0", "600"}) {
            runner.withPropertyValues("x402.client.clock-skew-seconds=" + value).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(ReflectionTestUtils.getField(
                                context.getBean(X402PaymentInterceptor.class), "clockSkewSeconds"))
                        .isEqualTo(Long.parseLong(value));
            });
        }
    }

    @Test
    void outOfRangeValuesFailStartupWithAClearMessage() {
        for (String value : new String[] {"601", "-1"}) {
            runner.withPropertyValues("x402.client.clock-skew-seconds=" + value).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("x402.client.clock-skew-seconds must be between 0 and 600");
            });
        }
    }
}
