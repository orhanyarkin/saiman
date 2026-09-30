package io.github.orhanyarkin.x402.autoconfigure;

import io.github.orhanyarkin.x402.client.PlaintextHostAllowlist;
import io.github.orhanyarkin.x402.client.PropertiesSpendGuard;
import io.github.orhanyarkin.x402.client.SpendGuard;
import io.github.orhanyarkin.x402.client.X402ClientProperties;
import io.github.orhanyarkin.x402.client.X402PaymentInterceptor;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.evm.PaymentSigner;
import io.github.orhanyarkin.x402.evm.PrivateKeyPaymentSigner;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Auto-configuration for the x402 {@code RestClient} payment interceptor.
 *
 * <p><b>Fails closed, no {@code enabled} flag (ADR-0008).</b> Exactly what exists depends on
 * {@code x402.client.private-key}:
 *
 * <ul>
 *   <li>the property is entirely absent — none of this configuration's beans are created; an
 *       application gets no {@link PaymentSigner}, {@link SpendGuard} or {@link
 *       X402PaymentInterceptor}, and starts normally.
 *   <li>the property is present, <em>even as an explicitly blank value</em> ({@code
 *       x402.client.private-key=}: Spring Boot's {@code @ConditionalOnProperty} treats "present"
 *       and "non-blank" as different things, and only checks the former) — a {@link
 *       PrivateKeyPaymentSigner} is created, so startup fails immediately if the value is blank or
 *       otherwise not a valid private key, then a {@link PropertiesSpendGuard} (startup fails if
 *       {@code x402.client.max-amount-per-request} or {@code x402.client.allowed-pay-to} is
 *       missing), then the {@link X402PaymentInterceptor} bean itself (the same two properties are
 *       checked again here, independently of {@link PropertiesSpendGuard}, since a consuming
 *       application may supply its own {@link SpendGuard} bean and skip {@link
 *       PropertiesSpendGuard} entirely — the interceptor still needs a maximum and an allowlist to
 *       pick an offer from the server). {@code x402.client.allowed-plaintext-hosts} is checked
 *       here too: exact host names only, and startup fails if the list is non-empty on any
 *       network other than the Base Sepolia testnet.
 * </ul>
 *
 * None of the failure messages above echo the rejected value (see {@link X402ClientProperties}).
 *
 * <p>This interceptor is never attached to a {@code RestClient.Builder} automatically: an
 * application injects the {@link X402PaymentInterceptor} bean and adds it to the specific builder
 * it wants to pay through.
 */
@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(X402ClientProperties.class)
public class X402ClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(X402Codec.class)
    X402Codec x402Codec() {
        return new X402Codec();
    }

    @Bean
    @ConditionalOnMissingBean(PaymentSigner.class)
    @ConditionalOnProperty(prefix = "x402.client", name = "private-key")
    PaymentSigner x402PaymentSigner(X402ClientProperties properties) {
        return new PrivateKeyPaymentSigner(requirePrivateKey(properties));
    }

    @Bean
    @ConditionalOnMissingBean(SpendGuard.class)
    @ConditionalOnBean(PaymentSigner.class)
    SpendGuard x402SpendGuard(X402ClientProperties properties) {
        return new PropertiesSpendGuard(requireMaxAmountPerRequest(properties), requireAllowedPayTo(properties));
    }

    @Bean
    @ConditionalOnMissingBean(X402PaymentInterceptor.class)
    @ConditionalOnBean({PaymentSigner.class, SpendGuard.class})
    X402PaymentInterceptor x402PaymentInterceptor(
            PaymentSigner signer,
            SpendGuard spendGuard,
            X402ClientProperties properties,
            X402Codec codec,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        return new X402PaymentInterceptor(
                signer,
                spendGuard,
                codec,
                requireMaxAmountPerRequest(properties),
                requireAllowedPayTo(properties),
                requireAllowedPlaintextHosts(properties, TestnetAssets.NETWORK),
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    /**
     * Checks {@code x402.client.allowed-plaintext-hosts} against {@code network}, the network this
     * client pays on. There is no network property (ADR-0008): the starter pays only on {@link
     * TestnetAssets#NETWORK}, so this is called with that constant; the parameter keeps the rule
     * ("never a plaintext exception off the testnet") explicit and testable should a network ever
     * become configurable.
     *
     * @throws IllegalStateException if the list is non-empty on a network other than Base
     *     Sepolia, or holds anything but exact host names; never echoes an entry
     */
    static List<String> requireAllowedPlaintextHosts(X402ClientProperties properties, String network) {
        return PlaintextHostAllowlist.requireValidAndNormalize(properties.allowedPlaintextHosts(), network);
    }

    /**
     * @throws IllegalStateException if {@code x402.client.private-key} is blank; unreachable in
     *     practice since the {@code @ConditionalOnProperty}-guarded bean that calls this only runs
     *     when the property is present, but {@link X402ClientProperties#privateKey()} is still
     *     {@code @Nullable} since the property is entirely optional at the type level
     */
    private static String requirePrivateKey(X402ClientProperties properties) {
        String privateKey = properties.privateKey();
        if (privateKey == null) {
            throw new IllegalStateException("x402.client.private-key must be set");
        }
        return privateKey;
    }

    /**
     * @throws IllegalStateException if {@code x402.client.max-amount-per-request} is missing or
     *     not positive; the message states the rule only, never {@code properties}' values
     */
    private static long requireMaxAmountPerRequest(X402ClientProperties properties) {
        Long maxAmountPerRequest = properties.maxAmountPerRequest();
        if (maxAmountPerRequest == null || maxAmountPerRequest <= 0) {
            throw new IllegalStateException(
                    "x402.client.max-amount-per-request must be set to a positive atomic amount when"
                            + " x402.client.private-key is configured");
        }
        return maxAmountPerRequest;
    }

    /**
     * @throws IllegalStateException if {@code x402.client.allowed-pay-to} is empty; the message
     *     states the rule only, never {@code properties}' values
     */
    private static List<String> requireAllowedPayTo(X402ClientProperties properties) {
        List<String> allowedPayTo = properties.allowedPayTo();
        if (allowedPayTo.isEmpty()) {
            throw new IllegalStateException("x402.client.allowed-pay-to must list at least one payee address when"
                    + " x402.client.private-key is configured");
        }
        return allowedPayTo;
    }
}
