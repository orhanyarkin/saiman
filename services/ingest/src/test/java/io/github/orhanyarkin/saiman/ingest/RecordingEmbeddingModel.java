package io.github.orhanyarkin.saiman.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic 1536-dimension embeddings from hashed word counts (identical text gives cosine
 * similarity 1, shared words give partial similarity), recording every call so tests can assert
 * "zero embedding calls on the second run".
 */
public class RecordingEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1536;

    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> texts = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failing = new AtomicBoolean();

    public int calls() {
        return calls.get();
    }

    public List<String> texts() {
        return new ArrayList<>(texts);
    }

    public void reset() {
        calls.set(0);
        texts.clear();
        failing.set(false);
    }

    public void failing(boolean value) {
        failing.set(value);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        calls.incrementAndGet();
        if (failing.get()) {
            throw new IllegalStateException("embedding provider down");
        }
        List<Embedding> out = new ArrayList<>();
        int i = 0;
        for (String text : request.getInstructions()) {
            texts.add(text);
            out.add(new Embedding(vector(text), i++));
        }
        return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
        return call(new EmbeddingRequest(List.of(document.getText() == null ? "" : document.getText()), null))
                .getResults()
                .get(0)
                .getOutput();
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    static float[] vector(String text) {
        float[] v = new float[DIMENSIONS];
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                v[Math.floorMod(token.hashCode(), DIMENSIONS)] += 1f;
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm == 0) {
            v[0] = 1f;
            return v;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) {
            v[i] *= scale;
        }
        return v;
    }
}
