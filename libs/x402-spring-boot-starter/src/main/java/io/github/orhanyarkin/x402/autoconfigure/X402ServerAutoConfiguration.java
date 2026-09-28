package io.github.orhanyarkin.x402.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Auto-configuration for server-side x402 payment enforcement.
 *
 * <p>Empty placeholder (M1 task T1): {@code @RequiresPayment}, {@code
 * RequiresPaymentInterceptor}, {@code X402SettlementFilter}, {@code PaymentNonceStore} and a
 * default {@code FacilitatorClient} bean are added once the {@code server} and {@code
 * facilitator} packages land (M1 tasks T2/T3), each guarded by {@code @ConditionalOnMissingBean}
 * so applications can override every piece.
 */
@AutoConfiguration
public class X402ServerAutoConfiguration {}
