// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.memory;

import io.jclaw.ports.Result;
import io.jclaw.ports.model.ModelProvider.ProviderFailure;

import java.util.Objects;

/**
 * Port to an embedding model. The only place text becomes a vector.
 *
 * <p>Separate from {@code ModelProvider} because the two are configured independently: a Claude
 * chat model has no embedding endpoint, an Ollama embedding model is a different model than the
 * chat one, and an operator may well run chat through one vendor and embeddings through another.
 *
 * <p>Failures are returned, not thrown, with the same sanitized {@link ProviderFailure} the chat
 * port uses. Callers degrade rather than fail: a memory whose embedding request failed is still
 * written, without a vector, and a search whose query could not be embedded still runs on the
 * lexical and recency rankings. Vector similarity is an additional signal, never a precondition.
 *
 * <p>{@link #disabled()} is the configured-off state, so wiring never has to special-case an
 * absent provider and every caller asks {@link #available()} in one place.
 */
public interface EmbeddingProvider {

    /** Stable provider id: {@code openai}, {@code ollama}, {@code local}, {@code openrouter}, {@code none}. */
    String id();

    /** The embedding model id; travels with every vector so spaces are never confused. */
    String model();

    /** Whether embeddings are configured at all. False for {@link #disabled()}. */
    boolean available();

    /** Embeds one text. */
    Result<Embedding, ProviderFailure> embed(String text);

    /** The configured-off provider: unavailable, and every request fails with a clear reason. */
    static EmbeddingProvider disabled() {
        return Disabled.INSTANCE;
    }

    /** The one implementation living in the port itself, because it carries no policy. */
    enum Disabled implements EmbeddingProvider {
        INSTANCE;

        @Override
        public String id() {
            return "none";
        }

        @Override
        public String model() {
            return "";
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Result<Embedding, ProviderFailure> embed(String text) {
            Objects.requireNonNull(text, "text");
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.UNKNOWN_MODEL, "no embedding provider configured"));
        }
    }
}
