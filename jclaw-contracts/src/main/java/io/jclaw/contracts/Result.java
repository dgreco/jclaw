// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Total result of a fallible operation: either an {@link Ok} value or an {@link Err} error.
 *
 * <p>Expected failures travel as values, not exceptions. A capability denial, a provider
 * refusal, or a budget exhaustion is an ordinary outcome the caller must handle — making it
 * a return type means the compiler asks the question rather than a stack trace answering it
 * later. Exceptions stay reserved for programming errors and truly exceptional host faults.
 *
 * @param <T> the success value
 * @param <E> the error value; in this codebase usually a stable redacted category
 */
public sealed interface Result<T, E> {

    record Ok<T, E>(T value) implements Result<T, E> {
        public Ok {
            Objects.requireNonNull(value, "value");
        }
    }

    record Err<T, E>(E error) implements Result<T, E> {
        public Err {
            Objects.requireNonNull(error, "error");
        }
    }

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    /**
     * Lifts a throwing supplier, mapping any exception through {@code onThrow}. Use at adapter
     * boundaries where a third-party API signals failure the only way it can.
     */
    static <T, E> Result<T, E> catching(Supplier<T> supplier, Function<Exception, E> onThrow) {
        try {
            return ok(supplier.get());
        } catch (Exception e) {
            return err(onThrow.apply(e));
        }
    }

    default boolean isOk() {
        return this instanceof Ok<T, E>;
    }

    default boolean isErr() {
        return this instanceof Err<T, E>;
    }

    default <U> Result<U, E> map(Function<? super T, ? extends U> fn) {
        return switch (this) {
            case Ok<T, E> ok -> ok(fn.apply(ok.value()));
            case Err<T, E> err -> err(err.error());
        };
    }

    default <F> Result<T, F> mapErr(Function<? super E, ? extends F> fn) {
        return switch (this) {
            case Ok<T, E> ok -> ok(ok.value());
            case Err<T, E> err -> err(fn.apply(err.error()));
        };
    }

    @SuppressWarnings("unchecked")
    default <U> Result<U, E> flatMap(Function<? super T, ? extends Result<U, E>> fn) {
        return switch (this) {
            case Ok<T, E> ok -> (Result<U, E>) fn.apply(ok.value());
            case Err<T, E> err -> err(err.error());
        };
    }

    /** Collapses both branches to a single value — the total way to consume a result. */
    default <U> U fold(Function<? super T, ? extends U> onOk, Function<? super E, ? extends U> onErr) {
        return switch (this) {
            case Ok<T, E> ok -> onOk.apply(ok.value());
            case Err<T, E> err -> onErr.apply(err.error());
        };
    }

    default T orElse(T fallback) {
        return switch (this) {
            case Ok<T, E> ok -> ok.value();
            case Err<T, E> ignored -> fallback;
        };
    }

    default T orElseGet(Function<? super E, ? extends T> fallback) {
        return switch (this) {
            case Ok<T, E> ok -> ok.value();
            case Err<T, E> err -> fallback.apply(err.error());
        };
    }

    /**
     * Unwraps the success value.
     *
     * @throws NoSuchElementException if this is an {@link Err}; reserved for call sites that
     *                                have already proven the result is {@code Ok}
     */
    default T orElseThrow() {
        return switch (this) {
            case Ok<T, E> ok -> ok.value();
            case Err<T, E> err -> throw new NoSuchElementException("result is Err: " + err.error());
        };
    }

    default Optional<T> toOptional() {
        return switch (this) {
            case Ok<T, E> ok -> Optional.of(ok.value());
            case Err<T, E> ignored -> Optional.empty();
        };
    }

    default Optional<E> errorAsOptional() {
        return switch (this) {
            case Ok<T, E> ignored -> Optional.empty();
            case Err<T, E> err -> Optional.of(err.error());
        };
    }
}
