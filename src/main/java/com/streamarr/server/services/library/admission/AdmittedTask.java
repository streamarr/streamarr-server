package com.streamarr.server.services.library.admission;

/**
 * The whole per-item unit of work, stages back to back, exactly as the scan or refresh ran it
 * before admission strategies existed.
 */
@FunctionalInterface
public interface AdmittedTask<T> {

  /** Runs every stage for {@code item}; a thrown exception is the item's failure. */
  TaskOutcome run(T item) throws Exception;
}
