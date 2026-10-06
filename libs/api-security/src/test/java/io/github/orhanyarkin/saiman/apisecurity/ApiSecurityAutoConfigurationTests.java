package io.github.orhanyarkin.saiman.apisecurity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

@ExtendWith(OutputCaptureExtension.class)
class ApiSecurityAutoConfigurationTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ApiSecurityAutoConfiguration.class));

    @Test
    void createsTheIntrospectorAndRoleHierarchyFromProperties() {
        runner.withPropertyValues(TestTokens.READER_PROPERTY, TestTokens.OPERATOR_PROPERTY)
                .run(context -> {
                    assertThat(context).hasSingleBean(StaticTokenIntrospector.class);
                    assertThat(context).hasSingleBean(RoleHierarchy.class);
                    assertThat(context.getBean(OpaqueTokenIntrospector.class)
                                    .introspect(TestTokens.OPERATOR)
                                    .getName())
                            .startsWith("operator:");
                });
    }

    @Test
    void operatorReachesReaderButNotTheOtherWayRound() {
        runner.withPropertyValues(TestTokens.READER_PROPERTY, TestTokens.OPERATOR_PROPERTY)
                .run(context -> {
                    RoleHierarchy hierarchy = context.getBean(RoleHierarchy.class);
                    assertThat(names(hierarchy.getReachableGrantedAuthorities(
                                    AuthorityUtils.createAuthorityList("ROLE_OPERATOR"))))
                            .containsExactlyInAnyOrder("ROLE_OPERATOR", "ROLE_READER");
                    assertThat(names(hierarchy.getReachableGrantedAuthorities(
                                    AuthorityUtils.createAuthorityList("ROLE_READER"))))
                            .containsExactly("ROLE_READER");
                    assertThat(names(hierarchy.getReachableGrantedAuthorities(
                                    AuthorityUtils.createAuthorityList("ROLE_SERVICE", "SERVICE_ledger"))))
                            .containsExactlyInAnyOrder("ROLE_SERVICE", "SERVICE_ledger");
                });
    }

    @Test
    void startupFailsWithoutHumanDigests() {
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("saiman.auth.reader-token-sha256 has no digest"));
    }

    @Test
    void startupFailsOnAMalformedDigestWithoutEchoingIt() {
        runner.withPropertyValues("saiman.auth.reader-token-sha256=" + TestTokens.READER, TestTokens.OPERATOR_PROPERTY)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("is not a SHA-256 digest")
                        .message()
                        .doesNotContain(TestTokens.READER));
    }

    @Test
    void startupFailsOnOneDigestInTwoRoles() {
        runner.withPropertyValues(
                        TestTokens.READER_PROPERTY, "saiman.auth.operator-token-sha256=" + TestTokens.READER_SHA256)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("repeats a digest"));
    }

    @Test
    void aServiceOnlyApplicationStartsWithServiceTokensAlone() {
        runner.withPropertyValues("saiman.auth.require-human-tokens=false", TestTokens.SERVICE_EVALS_PROPERTY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StaticTokenIntrospector.class)
                                    .introspect(TestTokens.SERVICE_EVALS)
                                    .getName())
                            .startsWith("service-evals:");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "off", "no", "0", "FALSE", "Off"})
    void everyFalseSpellingDisablesButOnlyWithTheAcknowledgement(String value) {
        runner.withPropertyValues("saiman.auth.enabled=" + value)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("saiman.auth.allow-disabled-insecure=true"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "off", "no", "0"})
    void acknowledgedDisabledModeCreatesNoVerifierAndWarnsLoudly(String value, CapturedOutput output) {
        runner.withPropertyValues("saiman.auth.enabled=" + value, "saiman.auth.allow-disabled-insecure=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OpaqueTokenIntrospector.class);
                    assertThat(context).doesNotHaveBean(RoleHierarchy.class);
                    assertThat(context.getBean(ApiTokenProperties.class).enabled())
                            .isFalse();
                });
        assertThat(output.getAll())
                .contains("INSECURE: saiman.auth.enabled=false", "authentication rules are NOT applied");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {TestTokens.READER_PROPERTY, TestTokens.OPERATOR_PROPERTY, TestTokens.SERVICE_LEDGER_PROPERTY})
    void digestsWhileDisabledFailStartup(String digestProperty) {
        runner.withPropertyValues("saiman.auth.enabled=off", "saiman.auth.allow-disabled-insecure=true", digestProperty)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("token digests are configured"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "on", "yes", "1"})
    void everyTrueSpellingEnables(String value) {
        runner.withPropertyValues(
                        "saiman.auth.enabled=" + value, TestTokens.READER_PROPERTY, TestTokens.OPERATOR_PROPERTY)
                .run(context -> {
                    assertThat(context).hasSingleBean(StaticTokenIntrospector.class);
                    assertThat(context).hasSingleBean(RoleHierarchy.class);
                });
    }

    @Test
    void theAcknowledgementAloneDoesNotDisable() {
        runner.withPropertyValues("saiman.auth.allow-disabled-insecure=true")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("saiman.auth.reader-token-sha256 has no digest"));
    }

    @Test
    void aValueThatIsNotABooleanFailsStartup() {
        runner.withPropertyValues("saiman.auth.enabled=maybe")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anApplicationIntrospectorReplacesTheStaticOne() {
        OpaqueTokenIntrospector custom = token -> {
            throw new UnsupportedOperationException();
        };
        runner.withBean(OpaqueTokenIntrospector.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed(); // no validation: the static introspector is never built
            assertThat(context).doesNotHaveBean(StaticTokenIntrospector.class);
            assertThat(context.getBean(OpaqueTokenIntrospector.class)).isSameAs(custom);
        });
    }

    /** The documented environment variable names must bind, including the map-of-records form. */
    @Test
    void documentedEnvironmentVariablesBind() {
        Map<String, Object> environment = Map.of(
                "SAIMAN_AUTH_READER_TOKEN_SHA256", TestTokens.READER_SHA256 + "," + TestTokens.digest("second"),
                "SAIMAN_AUTH_OPERATOR_TOKEN_SHA256", TestTokens.OPERATOR_SHA256,
                "SAIMAN_AUTH_SERVICE_TOKENS_LEDGER_SHA256", TestTokens.SERVICE_LEDGER_SHA256,
                "SAIMAN_AUTH_SERVICE_TOKENS_EVALS_SHA256", TestTokens.SERVICE_EVALS_SHA256,
                "SAIMAN_AUTH_REQUIRE_HUMAN_TOKENS", "true");
        runner.withInitializer(context -> context.getEnvironment()
                        .getPropertySources()
                        .replace(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                new SystemEnvironmentPropertySource(
                                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ApiTokenProperties properties = context.getBean(ApiTokenProperties.class);
                    assertThat(properties.readerTokenSha256())
                            .containsExactly(TestTokens.READER_SHA256, TestTokens.digest("second"));
                    assertThat(properties.operatorTokenSha256()).containsExactly(TestTokens.OPERATOR_SHA256);
                    assertThat(properties.serviceTokens())
                            .containsOnlyKeys("ledger", "evals")
                            .extractingByKey("ledger")
                            .extracting(ApiTokenProperties.ServiceToken::sha256)
                            .isEqualTo(TestTokens.SERVICE_LEDGER_SHA256);
                    StaticTokenIntrospector introspector = context.getBean(StaticTokenIntrospector.class);
                    assertThat(introspector.digestCount()).isEqualTo(5);
                    assertThat(introspector.introspect(TestTokens.SERVICE_EVALS).getName())
                            .startsWith("service-evals:");
                });
    }

    private static List<String> names(java.util.Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }
}
