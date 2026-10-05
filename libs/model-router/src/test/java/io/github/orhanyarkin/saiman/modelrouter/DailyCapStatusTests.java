package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.modelrouter.testing.FakeChatModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.mock.env.MockEnvironment;

/** {@link ModelRouter#dailyCap()} (ADR-0026): the numbers, and failing closed when the counter is unreadable. */
class DailyCapStatusTests {

    private static final ModelFactory FACTORY = new ModelFactory() {
        @Override
        public ChatModel chatModel(RouterProperties.Route route) {
            return new FakeChatModel("ok");
        }

        @Override
        public EmbeddingModel embeddingModel(RouterProperties.Embedding route) {
            return new FakeEmbeddingModel(1536);
        }
    };

    private static DefaultModelRouter router(CostGuard guard) {
        return new DefaultModelRouter(
                RouterPropertiesBinder.bind(new MockEnvironment()), FACTORY, guard, RouterMetrics.NOOP);
    }

    @Test
    void remainingIsTheCapMinusWhatIsSpentAndNeverNegative() {
        assertThat(new DailyCapStatus(Money.usdMicros(200_000), Money.usdMicros(700_000)).remaining())
                .isEqualTo(Money.usdMicros(500_000));
        DailyCapStatus over = new DailyCapStatus(Money.usdMicros(700_001), Money.usdMicros(700_000));
        assertThat(over.remaining()).isEqualTo(Money.usdMicros(0));
        assertThat(over.isReached()).isTrue();
    }

    @Test
    void theRouterReportsTodaysTotalAndTheConfiguredCap() {
        InMemoryCostGuard guard =
                new InMemoryCostGuard(700_000, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));
        guard.reserve(Money.usdMicros(123_456));

        DailyCapStatus status = router(guard).dailyCap();

        assertThat(status.spent()).isEqualTo(Money.usdMicros(123_456));
        assertThat(status.cap()).isEqualTo(Money.usdMicros(700_000));
        assertThat(status.remaining()).isEqualTo(Money.usdMicros(576_544));
    }

    @Test
    void anUnreadableCounterReportsTheCapAsReached() {
        CostGuard broken = new CostGuard() {
            @Override
            public Reservation reserve(Money estimate) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public void settle(Reservation reservation, Money actual) {}

            @Override
            public Money todayTotal() {
                throw new IllegalStateException("redis down");
            }
        };

        DailyCapStatus status = router(broken).dailyCap();

        assertThat(status.isReached()).isTrue();
        assertThat(status.remaining().isZero()).isTrue();
    }
}
