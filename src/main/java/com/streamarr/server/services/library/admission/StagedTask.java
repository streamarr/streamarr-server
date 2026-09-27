package com.streamarr.server.services.library.admission;

import java.util.Optional;

/**
 * An {@link AdmittedTask} whose work is also callable stage by stage, for strategies that hand
 * items between stages (a pipeline).
 *
 * <p>{@link #run} is not the same as {@code register → identify → persist}: {@code run} keeps the
 * original behavior, in which remote details are fetched inside the persist stage under the
 * provider-id mutex and only when the item does not exist yet. {@link #identify} fetches details
 * unconditionally and without the mutex, so a pipeline may fetch details the original path would
 * have skipped (a duplicate file of an existing movie). That extra request is the pipeline's cost.
 *
 * @param <T> the walked item (a path or a refresh target)
 * @param <R> a registered item that needs remote identification
 * @param <I> an identified item ready to persist
 */
public interface StagedTask<T, R, I> extends AdmittedTask<T> {

  /**
   * Database stage: creates or finds the item's record. Empty when the item short-circuits
   * (unsupported extension, already matched); the item is then finished.
   */
  Optional<R> register(T item) throws Exception;

  /** Remote stage: parse, search and fetch details. Holds no database connection. */
  I identify(R registered) throws Exception;

  /** Database stage: marks the failure or persists the match. */
  void persist(I identified) throws Exception;
}
