package com.streamarr.server.services.mutation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Aborts a mutation transaction with a typed rejection decided inside it — for writes whose
 * authorization must read state under the transaction's locks (ADR 0025's normalized transitions).
 * {@link MutationTransactions} rolls back and returns the rejection; it never escapes to callers.
 */
public class MutationRejection extends RuntimeException {

  private final transient List<Object> rejections;

  public MutationRejection(Object rejection) {
    this(List.of(rejection));
  }

  private MutationRejection(List<?> rejections) {
    super(
        rejections.stream()
            .map(rejection -> rejection.getClass().getSimpleName())
            .collect(Collectors.joining(", ", "mutation rejected: ", "")));
    this.rejections = List.copyOf(rejections);
  }

  /** Aborts with several reasons at once; {@link MutationTransactions} returns them in order. */
  public static MutationRejection ofReasons(List<?> rejections) {
    return new MutationRejection(rejections);
  }

  List<Object> rejections() {
    return rejections;
  }
}
