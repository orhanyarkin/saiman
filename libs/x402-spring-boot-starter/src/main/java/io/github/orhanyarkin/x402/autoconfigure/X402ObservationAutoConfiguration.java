package io.github.orhanyarkin.x402.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Auto-configuration for x402 observability: metrics and the payload-redacting observation
 * filter.
 *
 * <p>Empty placeholder (M1 task T1). Per the ADR-0006 amendment, once payment enforcement exists
 * (M1 tasks T2/T3) this registers: a counter {@code x402.payments} (tags {@code network}, {@code
 * outcome}), a distribution summary {@code x402.payment.amount} in atomic units, and an {@code
 * ObservationFilter} that drops any key-value whose name matches a payment header or contains
 * {@code signature}, {@code payload} or {@code authorization}, so payment payloads and signatures
 * never reach traces or metrics.
 */
@AutoConfiguration
public class X402ObservationAutoConfiguration {}
