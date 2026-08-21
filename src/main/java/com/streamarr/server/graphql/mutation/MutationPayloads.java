package com.streamarr.server.graphql.mutation;

import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.services.mutation.Outcome;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

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

  public static <P> P withUuid(String id, Function<UUID, P> whenValid, Supplier<P> whenMalformed) {
    try {
      return whenValid.apply(Ids.parseUuid(id));
    } catch (InvalidIdException _) {
      return whenMalformed.get();
    }
  }

  public static <T, E extends MutationError, P> P inputError(
      E error, BiFunction<Optional<T>, List<E>, P> envelope) {
    return envelope.apply(Optional.empty(), List.of(error));
  }
}
