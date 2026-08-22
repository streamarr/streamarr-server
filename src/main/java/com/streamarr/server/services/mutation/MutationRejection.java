package com.streamarr.server.services.mutation;

/**
 * Aborts a mutation transaction with a typed rejection decided inside it — for writes whose
 * authorization must read state under the transaction's locks (ADR 0025's normalized transitions).
 * {@link MutationTransactions} rolls back and returns the rejection; it never escapes to callers.
 */
public class MutationRejection extends RuntimeException {

  private final transient Object rejection;

  public MutationRejection(Object rejection) {
    super("mutation rejected: " + rejection.getClass().getSimpleName());
    this.rejection = rejection;
  }

  Object rejection() {
    return rejection;
  }
}
