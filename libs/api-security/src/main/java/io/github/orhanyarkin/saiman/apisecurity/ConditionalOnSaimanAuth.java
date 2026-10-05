package io.github.orhanyarkin.saiman.apisecurity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when API authentication is on ({@code enabled = true}, the default) or off ({@code enabled = false})
 * according to {@code saiman.auth.enabled}, converted exactly as {@link ApiTokenProperties} binds it: {@code false},
 * {@code off}, {@code no} and {@code 0} are all "off"; unset is "on"; a value that is not a boolean fails startup.
 *
 * <p>Services put {@code @ConditionalOnSaimanAuth} on their security configuration instead of {@code
 * @ConditionalOnProperty(havingValue = "true")}, which compares strings and would treat {@code 0} or {@code off} as
 * neither on nor off.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnSaimanAuth.OnSaimanAuthCondition.class)
public @interface ConditionalOnSaimanAuth {

    /** Whether to match when authentication is on ({@code true}) or off ({@code false}). */
    boolean enabled() default true;

    /** The condition behind the annotation. */
    final class OnSaimanAuthCondition extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            @Nullable
            Map<String, @Nullable Object> attributes =
                    metadata.getAnnotationAttributes(ConditionalOnSaimanAuth.class.getName());
            boolean wanted = attributes == null || Boolean.TRUE.equals(attributes.get("enabled"));
            boolean enabled = Binder.get(context.getEnvironment())
                    .bind(ApiSecurityAutoConfiguration.ENABLED_PROPERTY, Boolean.class)
                    .orElse(Boolean.TRUE);
            String state = ApiSecurityAutoConfiguration.ENABLED_PROPERTY + " binds to " + enabled;
            return enabled == wanted ? ConditionOutcome.match(state) : ConditionOutcome.noMatch(state);
        }
    }
}
