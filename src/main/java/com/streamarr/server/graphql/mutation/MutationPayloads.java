package com.streamarr.server.graphql.mutation;

import com.streamarr.server.services.mutation.Outcome;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Builds the ADR 0026 payload envelope from a service {@link Outcome}: an accepted outcome yields
 * the result and an empty {@code userErrors}; a rejected outcome yields an empty result and one
 * error per rejection. The invariant (result ⇔ no blocking errors) lives here once.
 */
public final class MutationPayloads {

  private MutationPayloads() {}

  public static <T, R, E extends MutationError, P> P payload(
      Outcome<T, R> outcome, Function<R, E> toError, BiFunction<Optional<T>, List<E>, P> envelope) {
    return outcome.fold(
        result -> envelope.apply(Optional.of(result), List.of()),
        rejections -> envelope.apply(Optional.empty(), rejections.stream().map(toError).toList()));
  }
}
