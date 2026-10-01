package io.github.orhanyarkin.saiman.ledger.pbt;

import java.security.SecureRandom;
import java.util.function.Supplier;

/**
 * Seeded property testing on plain JUnit 5 (ADR-0019). A property is one {@code @Test} that calls {@link #forAll}
 * with a try body; each try gets its own seed derived from one base seed, which comes from {@code
 * -Dsaiman.pbt.seed} (random otherwise). Every failure is an {@link AssertionError} whose message names the try,
 * its seed and the base seed, so it reproduces with {@code ./gradlew :services:ledger:test -Dsaiman.pbt.seed=<base>}
 * (the base seed is also printed once per JVM). The number of tries is {@value #DEFAULT_TRIES} for pure properties
 * and a quarter of that for database-backed ones, scaled together with {@code -Dsaiman.pbt.tries=<n>}. There is
 * no shrinking: sizes grow with the try index, so the first failing try is usually a small case, and {@link #check}
 * adds the generated case to the message.
 */
public final class Pbt {

    public static final String SEED_PROPERTY = "saiman.pbt.seed";

    public static final String TRIES_PROPERTY = "saiman.pbt.tries";

    /** Tries of a pure property unless {@code -Dsaiman.pbt.tries} says otherwise. */
    public static final int DEFAULT_TRIES = 200;

    /** The base seed of this JVM's run. */
    public static final long BASE_SEED = baseSeed();

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private Pbt() {}

    /** One try of a property. */
    @FunctionalInterface
    public interface Try {
        void run(int tryIndex, long seed);
    }

    /** Tries of a pure (in-memory) property. */
    public static int tries() {
        String configured = System.getProperty(TRIES_PROPERTY);
        int tries = configured == null || configured.isBlank() ? DEFAULT_TRIES : Integer.parseInt(configured.trim());
        if (tries < 1) {
            throw new IllegalArgumentException("-D" + TRIES_PROPERTY + " must be positive");
        }
        return tries;
    }

    /** Tries of a database-backed property: a quarter of {@link #tries()}, at least one. */
    public static int dbTries() {
        return Math.max(1, tries() / 4);
    }

    /**
     * Runs {@code tries} tries of a property, stopping at the first failure. A failure that {@link #check} already
     * described is rethrown as is; anything else (a generator failing) is wrapped with the seeds.
     */
    public static void forAll(int tries, Try body) {
        for (int tryIndex = 0; tryIndex < tries; tryIndex++) {
            long seed = seedOf(tryIndex);
            try {
                body.run(tryIndex, seed);
            } catch (PropertyFailure e) {
                throw e;
            } catch (AssertionError | RuntimeException e) {
                throw new PropertyFailure(tryIndex, seed, "(failed while generating the case)", e);
            }
        }
    }

    /** The seed of try {@code index} under the current base seed. */
    public static long seedOf(int index) {
        return BASE_SEED + GOLDEN_GAMMA * (index + 1L);
    }

    /** A size in {@code [1, max]} that grows linearly with the try index. */
    public static int size(int tryIndex, int tries, int max) {
        return 1 + (int) ((long) tryIndex * (max - 1) / Math.max(1, tries - 1));
    }

    /** Runs a property; on failure, rethrows with the seeds and the generated case. */
    public static void check(int tryIndex, long seed, Supplier<String> generatedCase, Runnable property) {
        try {
            property.run();
        } catch (AssertionError | RuntimeException e) {
            throw new PropertyFailure(tryIndex, seed, generatedCase.get(), e);
        }
    }

    /** A failed try: the message carries the try index, its seed, the base seed and the generated case. */
    static final class PropertyFailure extends AssertionError {
        private static final long serialVersionUID = 1L;

        PropertyFailure(int tryIndex, long seed, String generatedCase, Throwable cause) {
            super(
                    "Property failed at try " + tryIndex + " (seed " + seed + "). Reproduce with -D" + SEED_PROPERTY
                            + "=" + BASE_SEED + "\nGenerated case:\n" + generatedCase,
                    cause);
        }
    }

    private static long baseSeed() {
        String configured = System.getProperty(SEED_PROPERTY);
        long seed = configured == null || configured.isBlank()
                ? new SecureRandom().nextLong()
                : Long.parseLong(configured.trim());
        System.out.println("[pbt] base seed " + seed + " (-D" + SEED_PROPERTY + "=" + seed + ")");
        return seed;
    }
}
