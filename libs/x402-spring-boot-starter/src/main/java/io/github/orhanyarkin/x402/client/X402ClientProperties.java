package io.github.orhanyarkin.x402.client;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code x402.client.*} configuration for the buyer side of this starter.
 *
 * <p>Deliberately carries no Bean Validation annotations ({@code @NotNull}, {@code @Min}...):
 * Spring Boot's own configuration-property bind-failure report prints the rejected value of every
 * failed constraint, which would put {@link #privateKey()} in a startup log. {@code
 * X402ClientAutoConfiguration} and {@link PropertiesSpendGuard} validate these fields in plain
 * code instead, with messages that state the rule, never the rejected value.
 *
 * @param privateKey the buyer wallet's secp256k1 private key, 64 hex digits with or without a
 *     {@code 0x} prefix; a {@link io.github.orhanyarkin.x402.evm.PaymentSigner} bean — and every
 *     other client bean this starter provides — exists only once the {@code x402.client.private-key}
 *     property is present at all, <em>including as an explicitly blank value</em> ({@code
 *     x402.client.private-key=}): a blank value is "present" as far as Spring Boot's own {@code
 *     @ConditionalOnProperty} is concerned, so it still triggers bean creation, which then fails
 *     startup (a blank key is not a valid one) rather than silently behaving like an unset key
 * @param maxAmountPerRequest the largest single payment, in atomic units, this starter will ever
 *     sign; required once {@link #privateKey()} is set
 * @param allowedPayTo the wallet addresses this starter is allowed to pay, matched
 *     case-insensitively; required and non-empty once {@link #privateKey()} is set
 * @param allowedPlaintextHosts exact host names (besides loopback) the client may pay over plain
 *     {@code http}, e.g. {@code seller-api} on a private compose network; empty by default. No
 *     wildcard, suffix, port, path or range (see {@link PlaintextHostAllowlist}); startup fails if
 *     non-empty on any network other than the Base Sepolia testnet
 */
@ConfigurationProperties(prefix = "x402.client")
public record X402ClientProperties(
        @Nullable String privateKey,
        @Nullable Long maxAmountPerRequest,
        List<String> allowedPayTo,
        List<String> allowedPlaintextHosts) {

    public X402ClientProperties {
        allowedPayTo = allowedPayTo == null ? List.of() : List.copyOf(allowedPayTo);
        allowedPlaintextHosts = allowedPlaintextHosts == null ? List.of() : List.copyOf(allowedPlaintextHosts);
    }

    /** {@code true} if {@link #privateKey()} is set to a non-blank value. */
    public boolean hasPrivateKey() {
        return privateKey != null && !privateKey.isBlank();
    }

    /** Redacts {@link #privateKey()}: never let Boot's own bind-failure or debug report print it. */
    @Override
    public String toString() {
        return "X402ClientProperties[privateKey="
                + (hasPrivateKey() ? "REDACTED" : "unset")
                + ", maxAmountPerRequest="
                + maxAmountPerRequest
                + ", allowedPayTo="
                + allowedPayTo
                + ", allowedPlaintextHosts="
                + allowedPlaintextHosts
                + "]";
    }
}
