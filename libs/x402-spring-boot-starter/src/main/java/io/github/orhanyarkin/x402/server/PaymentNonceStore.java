package io.github.orhanyarkin.x402.server;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Claims x402 payment authorization nonces to prevent the same signed authorization from being
 * settled twice.
 *
 * <p>This is a pre-settlement, application-level guard: the on-chain EIP-3009 nonce recorded by
 * the token contract is the final guard either way (see {@link
 * io.github.orhanyarkin.x402.evm.Eip3009TypedData}), but claiming here first avoids sending an
 * obviously-replayed authorization to the facilitator at all, and rejects concurrent replays
 * (e.g. the same request fired twice in parallel) before either one reaches the facilitator.
 *
 * <p>Implementations must make {@link #claim(String, Duration)} atomic: under concurrent calls
 * with the same key, exactly one caller may observe a non-null token. See {@link
 * InMemoryPaymentNonceStore} (single instance, {@code ConcurrentHashMap}-based) and {@link
 * RedisPaymentNonceStore} (multi-instance, Redis {@code SET NX}). Register a bean of this type to
 * replace either default (this starter's auto-configuration backs off via {@code
 * @ConditionalOnMissingBean}).
 *
 * <p><b>Release is compare-and-delete, keyed by the claim token.</b> {@link #claim(String,
 * Duration)} returns an opaque token identifying that specific claim; {@link #release(String,
 * String)} only deletes the key if it is still held by the same token. Without this, a slow or
 * retried caller's release could delete a claim a *different* caller legitimately took over after
 * the first one expired -- silently reopening a nonce the second caller is still relying on being
 * claimed.
 */
public interface PaymentNonceStore {

    /**
     * Atomically claims {@code key} for {@code ttl}.
     *
     * @param key a caller-chosen key that uniquely identifies the payment authorization, e.g.
     *     {@code x402:nonce:{network}:{asset}:{canonicalNonceKey}}
     * @param ttl how long the claim should be held; callers should choose this to at least cover
     *     the authorization's remaining validity window
     * @return an opaque, non-empty claim token if this call claimed the key (it was not already
     *     claimed and unexpired); {@code null} if another caller already holds an unexpired claim
     *     on {@code key}
     */
    @Nullable
    String claim(String key, Duration ttl);

    /**
     * Releases a previously claimed {@code key}, but only if it is still held by {@code token} --
     * a compare-and-delete, not an unconditional delete. A no-op if {@code key} has already
     * expired and been re-claimed by someone else, or was already released.
     *
     * <p>Called, e.g., because the handler rejected the request with a non-2xx status and the
     * payment was never charged. A settlement failure or facilitator error deliberately does NOT
     * call this: the outcome is ambiguous (a third party may have front-run settlement on chain),
     * so the claim is kept and the request is never retried under the same nonce.
     *
     * @param key the key previously passed to {@link #claim(String, Duration)}
     * @param token the token {@link #claim(String, Duration)} returned for that call
     */
    void release(String key, String token);
}
