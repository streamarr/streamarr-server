package com.streamarr.server.services.concurrency;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets code running inside an admitted task say that it hit a retry, so the task reports a drop
 * even when the retried request then succeeded. {@link AdaptiveAdmission} binds the mark around
 * each task on the task's own thread.
 */
public final class AdmittedTaskRetries {

  static final ScopedValue<AtomicBoolean> RETRIED = ScopedValue.newInstance();

  private AdmittedTaskRetries() {}

  /** Marks the calling admitted task as retried. Outside an admitted task, it does nothing. */
  public static void markRetried() {
    if (RETRIED.isBound()) {
      RETRIED.get().set(true);
    }
  }
}
