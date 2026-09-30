package io.github.orhanyarkin.saiman.orchestrator.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.orhanyarkin.saiman.shared.money.Money;
import org.springframework.boot.jackson.JacksonMixin;

/**
 * Keeps {@link Money} on the wire as exactly {@code {atomicUnits, asset, decimals}}: without it,
 * Jackson also serialises the {@code isZero()} helper as a {@code zero} property. Registered for
 * Boot's mapper by {@link JacksonMixin} and applied by {@link RunEventCodec} to its own.
 */
@JacksonMixin(Money.class)
@JsonIgnoreProperties({"zero"})
public abstract class MoneyJsonMixin {}
