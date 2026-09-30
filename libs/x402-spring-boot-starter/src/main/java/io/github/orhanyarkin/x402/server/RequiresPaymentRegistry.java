package io.github.orhanyarkin.x402.server;

import io.github.orhanyarkin.x402.core.AssetAmount;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.facilitator.FacilitatorClient;
import io.github.orhanyarkin.x402.facilitator.SupportedResponse;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringValueResolver;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Resolves every {@link RequiresPayment}-annotated handler method into a fixed {@link
 * PaymentRequirements} offer, once at startup, and validates {@code x402.server.pay-to}, the
 * facilitator's advertised support and this starter's own interceptor wiring -- all iff at least
 * one such handler exists.
 *
 * <p>Runs as a {@link SmartInitializingSingleton} so it executes after {@link
 * RequestMappingHandlerMapping} has finished registering every controller method (bean
 * post-processing order does not otherwise guarantee that). This is the one place {@code
 * @RequiresPayment.price()} is resolved (via the Spring embedded value resolver, so both a
 * property placeholder and a literal atomic-unit string work) and parsed -- a missing, non-numeric
 * or zero price fails application startup rather than silently serving the resource for free
 * (ADR-0008: fail closed, no {@code enabled} flag).
 *
 * <p>{@code x402.server.pay-to} is validated in code, not with Bean Validation: a {@code
 * @NotBlank}/{@code @Pattern} failure would make Spring Boot's configuration-property bind-failure
 * report print the rejected value, and a wallet address (even though public) should not be
 * amplified into logs by a typo. The exception messages below state only the rule, never the
 * rejected value.
 *
 * <p>Public only because {@code X402ServerAutoConfiguration} (a different package) must construct
 * it; not otherwise intended as part of this starter's public API.
 */
public final class RequiresPaymentRegistry implements SmartInitializingSingleton, EmbeddedValueResolverAware {

    private static final Pattern ADDRESS_PATTERN = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final String ZERO_ADDRESS = "0x" + "0".repeat(40);

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;
    private final ObjectProvider<RequiresPaymentInterceptor> interceptor;
    private final FacilitatorClient facilitatorClient;
    private final X402ServerProperties properties;
    private @Nullable StringValueResolver valueResolver;
    private Map<Method, Entry> requirementsByMethod = Map.of();

    /**
     * Takes {@link ObjectProvider}s for {@link RequestMappingHandlerMapping} and {@link
     * RequiresPaymentInterceptor} rather than those types directly: this registry backs {@link
     * RequiresPaymentInterceptor}, which a {@code WebMvcConfigurer} bean registers into the
     * interceptor registry -- and Spring resolves every {@code WebMvcConfigurer} bean while it is
     * still in the middle of creating {@link RequestMappingHandlerMapping} itself. A direct
     * constructor dependency on either would therefore be a real circular dependency; {@link
     * ObjectProvider#getObject()} defers the lookup to {@link #afterSingletonsInstantiated()},
     * which only runs once every singleton (including both of those) exists.
     */
    public RequiresPaymentRegistry(
            ObjectProvider<RequestMappingHandlerMapping> handlerMapping,
            ObjectProvider<RequiresPaymentInterceptor> interceptor,
            FacilitatorClient facilitatorClient,
            X402ServerProperties properties) {
        this.handlerMapping = handlerMapping;
        this.interceptor = interceptor;
        this.facilitatorClient = facilitatorClient;
        this.properties = properties;
    }

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        this.valueResolver = resolver;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<HandlerMethod> paidHandlers = handlerMapping.getObject().getHandlerMethods().values().stream()
                .filter(handlerMethod ->
                        AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), RequiresPayment.class))
                .toList();
        if (paidHandlers.isEmpty()) {
            this.requirementsByMethod = Map.of();
            return;
        }
        String payTo = validatePayTo(properties.payTo());
        validateFacilitatorSupportsTestnet();
        validateInterceptorIsRegistered();

        Map<Method, Entry> built = new HashMap<>();
        for (HandlerMethod handlerMethod : paidHandlers) {
            Method method = handlerMethod.getMethod();
            RequiresPayment annotation = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPayment.class);
            if (annotation == null) {
                continue;
            }
            requireSynchronousReturnType(method);
            built.put(method, buildEntry(annotation, payTo));
        }
        this.requirementsByMethod = Map.copyOf(built);
    }

    /** The fixed payment offer and description for {@code method}, or {@code null} if not a paid handler. */
    @Nullable
    Entry entryFor(Method method) {
        return requirementsByMethod.get(method);
    }

    /**
     * Whether any handler in this application is annotated {@link RequiresPayment}. Public (unlike
     * {@link #entryFor}) because {@code X402RedactingObservationFilter} (a different package) uses
     * it to skip redaction work entirely in an application with no paid endpoints at all.
     */
    public boolean hasPaidHandlers() {
        return !requirementsByMethod.isEmpty();
    }

    private Entry buildEntry(RequiresPayment annotation, String payTo) {
        String resolvedPrice = resolve(annotation.price());
        AssetAmount amount;
        try {
            amount = AssetAmount.parse(resolvedPrice);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "@RequiresPayment price must resolve to a positive decimal atomic-unit amount");
        }
        if (amount.atomicUnits() == 0) {
            throw new IllegalStateException("@RequiresPayment price must be greater than zero");
        }
        PaymentRequirements offer = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                amount.toWireValue(),
                TestnetAssets.USDC_ADDRESS,
                payTo,
                properties.maxTimeoutSeconds(),
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        int minWindow = annotation.minWindowSeconds();
        if (minWindow < 0
                || minWindow > properties.maxTimeoutSeconds() + RequiresPaymentInterceptor.CLOCK_SKEW.toSeconds()) {
            throw new IllegalStateException("@RequiresPayment minWindowSeconds must be between 0 and"
                    + " x402.server.max-timeout-seconds plus the clock skew allowance");
        }
        return new Entry(offer, annotation.description(), minWindow);
    }

    private String resolve(String value) {
        if (valueResolver == null) {
            return value;
        }
        String resolved = valueResolver.resolveStringValue(value);
        return resolved != null ? resolved : value;
    }

    private static String validatePayTo(@Nullable String payTo) {
        if (payTo == null || payTo.isBlank()) {
            throw new IllegalStateException("x402.server.pay-to must be set to a 0x-prefixed 20-byte hex address when a"
                    + " @RequiresPayment handler is present");
        }
        if (!ADDRESS_PATTERN.matcher(payTo).matches()) {
            throw new IllegalStateException("x402.server.pay-to must be a 0x-prefixed 20-byte hex address");
        }
        if (payTo.equalsIgnoreCase(ZERO_ADDRESS)) {
            throw new IllegalStateException("x402.server.pay-to must not be the zero address");
        }
        if (payTo.equalsIgnoreCase(TestnetAssets.USDC_ADDRESS)) {
            throw new IllegalStateException("x402.server.pay-to must not be the USDC contract address");
        }
        return payTo;
    }

    /**
     * Fails closed unless the configured facilitator advertises {@code exact} on {@link
     * TestnetAssets#NETWORK}. Only called when a {@code @RequiresPayment} handler exists, so an
     * application (or test) with none never depends on facilitator reachability at startup.
     */
    private void validateFacilitatorSupportsTestnet() {
        SupportedResponse response = facilitatorClient.supported();
        boolean supportsExactOnTestnet = response.kinds().stream()
                .anyMatch(kind -> TestnetAssets.SCHEME_EXACT.equals(kind.scheme())
                        && TestnetAssets.NETWORK.equals(kind.network()));
        if (!supportsExactOnTestnet) {
            throw new IllegalStateException(
                    "the configured x402 facilitator does not advertise support for the exact scheme on "
                            + TestnetAssets.NETWORK);
        }
    }

    /**
     * Fails closed if {@link RequiresPaymentInterceptor} is not actually registered on {@link
     * RequestMappingHandlerMapping}'s interceptor chain -- e.g. an application that extends {@code
     * WebMvcConfigurationSupport} directly instead of implementing {@code WebMvcConfigurer} still
     * gets the interceptor *bean*, but Spring never calls {@code addInterceptors} on it, so nothing
     * would ever actually check payment. {@link X402SettlementFilter} carries a runtime backstop
     * for the same failure mode (see {@code X402PaymentAttempt#interceptorRan()}), since this
     * startup check cannot rule out every way a specific request could bypass the interceptor.
     */
    private void validateInterceptorIsRegistered() {
        RequiresPaymentInterceptor requiresPaymentInterceptor = interceptor.getObject();
        HandlerInterceptor @Nullable [] adaptedInterceptors =
                handlerMapping.getObject().getAdaptedInterceptors();
        if (adaptedInterceptors != null) {
            for (HandlerInterceptor candidate : adaptedInterceptors) {
                if (candidate == requiresPaymentInterceptor) {
                    return;
                }
                if (candidate instanceof MappedInterceptor mapped
                        && mapped.getInterceptor() == requiresPaymentInterceptor) {
                    return;
                }
            }
        }
        throw new IllegalStateException(
                "RequiresPaymentInterceptor is not registered on the RequestMappingHandlerMapping, so a"
                        + " @RequiresPayment handler would never actually be payment-checked. This usually"
                        + " means the application overrides WebMvcConfigurationSupport directly instead of"
                        + " implementing WebMvcConfigurer, which bypasses this starter's interceptor"
                        + " registration.");
    }

    /**
     * Fails closed if {@code method}'s return type (including inside {@link ResponseEntity}) is
     * asynchronous: {@link Callable}, {@link WebAsyncTask}, {@link DeferredResult}, {@link
     * CompletionStage}/{@link Future}, {@link ResponseBodyEmitter} (and {@code SseEmitter}, a
     * subclass), {@link StreamingResponseBody}, or anything {@link
     * ReactiveAdapterRegistry#getSharedInstance()} recognises (e.g. a Reactor {@code Mono}/{@code
     * Flux}). {@link X402SettlementFilter} buffers the response and decides whether to flush or
     * discard it synchronously, right after {@code FilterChain.doFilter} returns -- but for an
     * async handler, that call returns as soon as async processing *starts*, long before the
     * handler has actually produced a result, so the filter would settle (or fail to settle) based
     * on an incomplete response. Async support is out of scope for this starter; this is enforced
     * at startup rather than discovered as a money bug at request time.
     */
    private static void requireSynchronousReturnType(Method method) {
        Class<?> returnType = method.getReturnType();
        Class<?> effectiveType = returnType;
        if (ResponseEntity.class.isAssignableFrom(returnType)
                && method.getGenericReturnType() instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1) {
            Type argument = parameterized.getActualTypeArguments()[0];
            if (argument instanceof Class<?> argumentClass) {
                effectiveType = argumentClass;
            } else if (argument instanceof ParameterizedType nestedParameterized
                    && nestedParameterized.getRawType() instanceof Class<?> rawClass) {
                effectiveType = rawClass;
            }
        }
        boolean isKnownAsyncType = Callable.class.isAssignableFrom(effectiveType)
                || WebAsyncTask.class.isAssignableFrom(effectiveType)
                || DeferredResult.class.isAssignableFrom(effectiveType)
                || CompletionStage.class.isAssignableFrom(effectiveType)
                || Future.class.isAssignableFrom(effectiveType)
                || ResponseBodyEmitter.class.isAssignableFrom(effectiveType)
                || StreamingResponseBody.class.isAssignableFrom(effectiveType);
        boolean isReactiveType = ReactiveAdapterRegistry.getSharedInstance().getAdapter(effectiveType) != null;
        if (isKnownAsyncType || isReactiveType) {
            throw new IllegalStateException(
                    "@RequiresPayment does not support asynchronous or reactive handler return types");
        }
    }

    /**
     * A resolved payment offer for one handler method, plus its human-readable description and the
     * handler's own minimum authorization window in seconds ({@code 0} = starter default only).
     */
    record Entry(PaymentRequirements offer, String description, int minWindowSeconds) {}
}
