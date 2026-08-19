package com.streamarr.server.services.mutation;

import java.util.List;

/**
 * The protocol-independent result of a mutation service (ADR 0026): the work was accepted and
 * produced {@code T}, or it was rejected for one or more typed reasons {@code R} and nothing was
 * written. Services never throw an expected rejection; exceptions are reserved for failure and the
 * request gates.
 */
public sealed interface Outcome<T, R> {

  record Accepted<T, R>(T result) implements Outcome<T, R> {}

  record Rejected<T, R>(List<R> rejections) implements Outcome<T, R> {

    public Rejected {
      if (rejections.isEmpty()) {
        throw new IllegalArgumentException("A rejection needs at least one reason");
      }
      rejections = List.copyOf(rejections);
    }
  }

  static <T, R> Outcome<T, R> accepted(T result) {
    return new Accepted<>(result);
  }

  static <T, R> Outcome<T, R> rejected(List<R> rejections) {
    return new Rejected<>(rejections);
  }

  static <T, R> Outcome<T, R> rejected(R rejection) {
    return new Rejected<>(List.of(rejection));
  }
}
