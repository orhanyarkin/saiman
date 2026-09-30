package io.github.orhanyarkin.saiman.modelrouter;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * A route's primary OpenAI model with its backup model from the same provider (OpenAI adapter; the
 * provider error types are referenced here only).
 *
 * <p>The backup is used only when the primary failed to <em>connect or answer</em>: a connect error or
 * timeout, HTTP 429 or HTTP 5xx. Never for another 4xx (the request itself is wrong, a second model
 * would be wrong too and would double the bill), never for {@link RequestNotSentException}, and for a
 * stream never after the first element was delivered (the caller already holds part of an answer).
 *
 * <p>A Resilience4j circuit breaker per route counts those failures. While it is open the primary is
 * skipped and calls go straight to the backup; after the wait it probes the primary again. The
 * breaker is driven by hand ({@code tryAcquirePermission} / {@code onSuccess} / {@code onError}) because
 * only some failures count. A failed backup call is not the route's failure: its exception propagates
 * with the primary's failure attached as suppressed.
 *
 * <p>Both models enforce their own route limits on the wire ({@link OpenAiModelFactory}); the cost
 * reservation in front of this model is priced at the more expensive of the two ({@link RouteCosting}).
 */
final class OpenAiFailoverChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiFailoverChatModel.class);

    /** 10-call window, opens at 50 % failures after 5 calls, probes again after 30 s. */
    static CircuitBreakerConfig defaultBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
    }

    private final ChatModel primary;
    private final ChatModel backup;
    private final CircuitBreaker breaker;

    OpenAiFailoverChatModel(ChatModel primary, ChatModel backup, CircuitBreaker breaker) {
        this.primary = primary;
        this.backup = backup;
        this.breaker = breaker;
    }

    /** The primary's options: Spring AI builds requests from them (tool calling needs tool-capable options). */
    @Override
    public ChatOptions getOptions() {
        return primary.getOptions();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (!breaker.tryAcquirePermission()) {
            return backup.call(prompt);
        }
        long start = System.nanoTime();
        try {
            ChatResponse response = primary.call(prompt);
            breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return response;
        } catch (RuntimeException e) {
            if (!fallbackable(e)) {
                breaker.releasePermission();
                throw e;
            }
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            log.warn("Primary model failed ({}), using the route's fallback model", describe(e));
            try {
                return backup.call(prompt);
            } catch (RuntimeException second) {
                second.addSuppressed(e);
                throw second;
            }
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            if (!breaker.tryAcquirePermission()) {
                return backup.stream(prompt);
            }
            long start = System.nanoTime();
            AtomicBoolean delivered = new AtomicBoolean();
            AtomicBoolean settled = new AtomicBoolean();
            return primary.stream(prompt)
                    .doOnNext(element -> delivered.set(true))
                    .doOnComplete(() -> {
                        if (settled.compareAndSet(false, true)) {
                            breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                        }
                    })
                    .doOnCancel(() -> {
                        if (settled.compareAndSet(false, true)) {
                            breaker.releasePermission();
                        }
                    })
                    .onErrorResume(e -> {
                        if (!settled.compareAndSet(false, true)) {
                            return Flux.error(e);
                        }
                        if (delivered.get() || !fallbackable(e)) {
                            breaker.releasePermission();
                            return Flux.error(e);
                        }
                        breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
                        log.warn("Primary model failed ({}), using the route's fallback model", describe(e));
                        return backup.stream(prompt).onErrorMap(second -> {
                            second.addSuppressed(e);
                            return second;
                        });
                    });
        });
    }

    /** True for connect errors, timeouts, HTTP 429 and HTTP 5xx; looks through wrapper exceptions. */
    static boolean fallbackable(Throwable failure) {
        Throwable t = failure;
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof RequestNotSentException) {
                return false;
            }
            if (t instanceof OpenAIServiceException service) {
                int status = service.statusCode();
                return status == 429 || status >= 500;
            }
            if (t instanceof OpenAIIoException || t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /** Exception class and status only: messages can echo request data. */
    private static String describe(Throwable failure) {
        if (failure instanceof OpenAIServiceException service) {
            return service.getClass().getSimpleName() + " " + service.statusCode();
        }
        return failure.getClass().getSimpleName();
    }
}
