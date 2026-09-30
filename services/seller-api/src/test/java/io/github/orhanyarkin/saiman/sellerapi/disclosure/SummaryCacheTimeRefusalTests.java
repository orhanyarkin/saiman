package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.orhanyarkin.saiman.sellerapi.llm.Deadline;
import io.github.orhanyarkin.saiman.sellerapi.llm.LlmRunProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * A request without enough time left must never take the single-flight lock (it would become the
 * winner and could not generate) and must never write a negative-cache entry (that would let one
 * payer poison the ticker for everyone).
 */
class SummaryCacheTimeRefusalTests {

    @Test
    @SuppressWarnings("unchecked")
    void aRequestWithoutTimeNeverTakesTheLockNorWritesANegativeCacheEntry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        when(redis.hasKey(anyString())).thenReturn(false);
        DisclosureSummaryCache cache = new DisclosureSummaryCache(
                redis,
                JsonMapper.builder().build(),
                new DisclosureProperties("rag", Duration.ofHours(1), Duration.ofMinutes(1)),
                new LlmRunProperties(
                        2, 30, 100, Duration.ofSeconds(25), Duration.ofSeconds(5), Duration.ofSeconds(120)));
        AtomicInteger generations = new AtomicInteger();

        assertThatThrownBy(() -> cache.getOrGenerate(
                        "THYAO",
                        "v1",
                        Deadline.after(Duration.ofSeconds(1)),
                        () -> {
                            throw new InsufficientTimeException();
                        },
                        () -> {
                            generations.incrementAndGet();
                            return null;
                        }))
                .isInstanceOf(InsufficientTimeException.class);

        assertThat(generations).hasValue(0);
        verify(ops, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
