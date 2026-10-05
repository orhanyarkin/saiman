package io.github.orhanyarkin.saiman.evals.metrics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Disclosure-level view of a chunk ranking. Chunk ids look like {@code kap:<disclosureIndex>:<nnnn>}; the
 * golden set labels whole disclosures, so a ranking of chunks is reduced to the ranking of their
 * disclosures, keeping the first (best) rank of each.
 */
public final class Disclosures {

    private static final Pattern CHUNK_ID = Pattern.compile("kap:(\\d{1,10}):\\d{4}");

    private Disclosures() {}

    /** The disclosure index of a chunk id; throws for an id that is not {@code kap:<index>:<nnnn>}. */
    public static long indexOf(String chunkId) {
        Matcher matcher = CHUNK_ID.matcher(chunkId);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a KAP chunk id: " + chunkId);
        }
        return Long.parseLong(matcher.group(1));
    }

    /** Distinct disclosure indexes in order of first appearance. */
    public static List<Long> dedupe(List<String> chunkIds) {
        Set<Long> seen = new HashSet<>();
        List<Long> out = new ArrayList<>();
        for (String chunkId : chunkIds) {
            long index = indexOf(chunkId);
            if (seen.add(index)) {
                out.add(index);
            }
        }
        return out;
    }
}
