package io.github.orhanyarkin.x402;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Auto-configuration entry point for the x402 Spring Boot starter.
 *
 * <p>Registered in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} so it is
 * picked up automatically by any application that depends on this starter.
 *
 * <p>This is an empty placeholder in M0 (skeleton milestone): server-side payment enforcement
 * ({@code @RequiresPayment}, the settlement {@code OncePerRequestFilter}, the pluggable {@code
 * FacilitatorClient}) and the client-side {@code RestClient} interceptor are added once the x402
 * wire formats are wired up on top of the official x402 Java SDK.
 */
@AutoConfiguration
public class X402AutoConfiguration {}
