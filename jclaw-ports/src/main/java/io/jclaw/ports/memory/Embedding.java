// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.memory;

import java.util.Arrays;
import java.util.Objects;

/**
 * A text embedding: a dense vector from a named model.
 *
 * <p>The model id travels with the vector because vectors from different models, or different
 * versions of one model, are not comparable: a cosine between them is a number with no meaning.
 * Ranking only compares embeddings that share a space, and an operator who switches embedding
 * model gets memories re-embedded rather than silently mis-ranked.
 *
 * <p>Immutable: the array is copied in and copied out.
 */
public record Embedding(String model, float[] values) {

    public Embedding {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(values, "values");
        if (model.isBlank()) {
            throw new IllegalArgumentException("embedding model must not be blank");
        }
        if (values.length == 0) {
            throw new IllegalArgumentException("embedding must have at least one dimension");
        }
        values = values.clone();
    }

    @Override
    public float[] values() {
        return values.clone();
    }

    public int dimensions() {
        return values.length;
    }

    /** Whether two embeddings may be compared: same model, same dimensionality. */
    public boolean sameSpaceAs(Embedding other) {
        Objects.requireNonNull(other, "other");
        return model.equals(other.model) && values.length == other.values.length;
    }

    /**
     * Cosine similarity with another embedding in the same space.
     *
     * @throws IllegalArgumentException when the spaces differ; comparing them would be a bug,
     *                                  not a low score
     */
    public double cosine(Embedding other) {
        if (!sameSpaceAs(other)) {
            throw new IllegalArgumentException(
                    "cannot compare embeddings from different spaces: " + describe() + " vs "
                            + other.describe());
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < values.length; i++) {
            dot += values[i] * other.values[i];
            normA += values[i] * values[i];
            normB += other.values[i] * other.values[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String describe() {
        return model + "[" + values.length + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Embedding other
                && model.equals(other.model)
                && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(model, Arrays.hashCode(values));
    }

    @Override
    public String toString() {
        return "Embedding" + describe();
    }
}
