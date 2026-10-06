package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code seller.eval.*}: limits of the internal eval API ({@code POST /internal/v1/eval/questions}, ADR-0025), which
 * runs the paid answer service without a payment. Enforced by the run guard under the caller key {@code evals}; the
 * router's daily USD cap applies on top.
 *
 * @param maxInFlight eval answers running at the same time
 * @param maxRunsPerHour eval answers started in any rolling hour
 * @param maxRunsPerDay eval answers started per UTC day. Eval calls draw on the same router day cap as paid traffic, so
 *     this bounds what a looping harness or a leaked evals token can take from it (ADR-0025)
 */
@ConfigurationProperties("seller.eval")
record EvalProperties(
        @DefaultValue("1") int maxInFlight,
        @DefaultValue("60") int maxRunsPerHour,
        @DefaultValue("100") int maxRunsPerDay) {

    EvalProperties {
        if (maxInFlight < 1 || maxRunsPerHour < 1 || maxRunsPerDay < 1) {
            throw new IllegalArgumentException("seller.eval limits must be positive");
        }
    }
}
