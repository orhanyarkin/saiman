package io.github.orhanyarkin.saiman.ledger.payment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;

/** Generators for the property tests (ADR-0019): what producers may report about one authorization. */
public final class Stories {

    /** One kind of report. A story uses each at most once; redeliveries repeat the same event object. */
    public enum Step {
        AUTHORIZED,
        BUYER_SETTLED,
        SELLER_SETTLED,
        BUYER_EXPIRED_UNUSED,
        SELLER_SETTLE_FAILED
    }

    private Stories() {}

    /** A non-empty random subset of the steps, in a random "canonical" order (any combination, even contradictory). */
    public static List<Step> story(SplittableRandom random) {
        List<Step> steps = new ArrayList<>();
        for (Step step : EnumSet.allOf(Step.class)) {
            if (random.nextInt(3) > 0) {
                steps.add(step);
            }
        }
        if (steps.isEmpty()) {
            steps.add(Step.values()[random.nextInt(Step.values().length)]);
        }
        Collections.shuffle(steps, new Random(random.nextLong()));
        return steps;
    }

    /** Amounts 1..10^12 atomic units, skewed towards small ones. */
    public static long amount(SplittableRandom random) {
        return random.nextBoolean() ? random.nextLong(1, 100_001) : random.nextLong(1, 1_000_000_000_001L);
    }

    public static PaymentFact fact(TestPayment payment, Step step) {
        return switch (step) {
            case AUTHORIZED -> PaymentFact.of(payment.authorized());
            case BUYER_SETTLED -> PaymentFact.of(payment.buyerSettled());
            case SELLER_SETTLED -> PaymentFact.of(payment.sellerSettled());
            case BUYER_EXPIRED_UNUSED -> PaymentFact.of(payment.buyerExpiredUnused());
            case SELLER_SETTLE_FAILED -> PaymentFact.of(payment.sellerSettleFailed());
        };
    }

    /** The events in a random order with up to {@code maxDuplicates} redeliveries (the same object again). */
    public static <T> List<T> permutedWithDuplicates(List<T> events, int maxDuplicates, SplittableRandom random) {
        List<T> delivered = new ArrayList<>(events);
        int duplicates = random.nextInt(maxDuplicates + 1);
        for (int i = 0; i < duplicates; i++) {
            delivered.add(events.get(random.nextInt(events.size())));
        }
        Collections.shuffle(delivered, new Random(random.nextLong()));
        return delivered;
    }
}
