package io.github.orhanyarkin.x402.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Auto-configuration for the x402 {@code RestClient} payment interceptor.
 *
 * <p>Empty placeholder (M1 task T1): {@code X402PaymentInterceptor}, {@code SpendGuard} (via
 * {@code PropertiesSpendGuard}, {@code @ConditionalOnMissingBean}) and a {@link
 * io.github.orhanyarkin.x402.evm.PaymentSigner} bean sourced from {@code x402.client.private-key}
 * are added once the {@code client} package lands (M1 task T3). Per ADR-0008 this starter fails
 * closed: without a configured private key, per-request maximum and payee allowlist, the
 * interceptor bean does not exist and the application does not start.
 */
@AutoConfiguration
public class X402ClientAutoConfiguration {}
