package io.github.orhanyarkin.saiman.ledger.pbt;

import java.security.SecureRandom;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Seeded property testing on plain JUnit 5 (ADR-0019). A property is a {@code @ParameterizedTest} over
 * {@link #tries(int)}; each try gets its own seed derived from one base seed, which comes from
 * {@code -Dsaiman.pbt.seed} (random otherwise) and is printed in every failure, so any failure reproduces with
 * {@code ./gradlew :services:ledger:test -Dsaiman.pbt.seed=<base>}. There is no shrinking: sizes grow with the try
 * index, so the first failing try is usually a small case, and {@link #check} prints the generated case.
 */
public final class Pbt {

    public static final String SEED_PROPERTY = "saiman.pbt.seed";

    /** The base seed of this JVM's run. */
    public static final long BASE_SEED = baseSeed();

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private Pbt() {}

    /** Arguments {@code (tryIndex, seed)} for {@code tries} tries. */
    public static Stream<Arguments> tries(int tries) {
        return IntStream.range(0, tries).mapToObj(i -> Arguments.of(i, seedOf(i)));
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
            throw new AssertionError(
                    "Property failed at try " + tryIndex + " (seed " + seed + "). Reproduce with -D" + SEED_PROPERTY
                            + "=" + BASE_SEED + "\nGenerated case:\n" + generatedCase.get(),
                    e);
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
