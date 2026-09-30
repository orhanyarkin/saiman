package io.github.orhanyarkin.saiman.modelrouter.testing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

/**
 * Deterministic embedding model for tests: the same text always maps to the same unit vector of
 * the configured dimension (a SHA-256 seeded pseudo-random direction), different texts map to
 * different vectors. No key, no network. Counts calls and inputs so tests can assert "zero calls".
 * Reports usage of {@code ceil(chars / 4)} tokens per input.
 */
public final class FakeEmbeddingModel implements EmbeddingModel {

    private final int dimensions;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger inputs = new AtomicInteger();

    public FakeEmbeddingModel(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.dimensions = dimensions;
    }

    /** Number of {@link #call(EmbeddingRequest)} invocations. */
    public int callCount() {
        return calls.get();
    }

    /** Total number of texts embedded. */
    public int inputCount() {
        return inputs.get();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        calls.incrementAndGet();
        List<Embedding> results = new ArrayList<>();
        int tokens = 0;
        for (String text : request.getInstructions()) {
            inputs.incrementAndGet();
            results.add(new Embedding(vectorFor(text), results.size()));
            tokens += (text.length() + 3) / 4;
        }
        return new EmbeddingResponse(results, new EmbeddingResponseMetadata("", new DefaultUsage(tokens, 0)));
    }

    @Override
    public float[] embed(Document document) {
        String content = getEmbeddingContent(document);
        return vectorFor(content == null ? "" : content);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    /** The unit vector for {@code text}; usable directly in expectations. */
    public float[] vectorFor(String text) {
        long seed = ByteBuffer.wrap(sha256(text)).getLong();
        SplittableRandom random = new SplittableRandom(seed);
        float[] vector = new float[dimensions];
        double norm = 0;
        for (int i = 0; i < dimensions; i++) {
            double component = random.nextDouble() - 0.5;
            vector[i] = (float) component;
            norm += component * component;
        }
        double length = Math.sqrt(norm);
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) (vector[i] / length);
        }
        return vector;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
