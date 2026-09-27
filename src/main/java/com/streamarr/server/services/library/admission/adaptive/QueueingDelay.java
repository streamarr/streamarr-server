package com.streamarr.server.services.library.admission.adaptive;

import java.util.concurrent.atomic.LongAdder;

/**
 * Per-task queueing time for the adaptive admission: waits at the TMDB gate (from the task's
 * ticket) and waits for a pooled database connection (observed here, on the task's own thread).
 *
 * <p>{@link QueueingRttLimit} feeds the limit this queueing time on top of the task's smallest
 * service time instead of the whole-task round trip, whose spread (TMDB latency, cast size) a
 * delay-based limit otherwise reads as queueing.
 */
public final class QueueingDelay {

  /** Pool waits of the admitted task running on this thread. */
  static final ScopedValue<LongAdder> POOL_WAIT = ScopedValue.newInstance();

  /** The finished task's total queueing time, bound while its permit is released. */
  static final ScopedValue<Long> SAMPLE_WAIT = ScopedValue.newInstance();

  private QueueingDelay() {}

  /** Adds a connection-pool wait to the admitted task running on this thread, if any. */
  public static void addPoolWait(long nanos) {
    if (POOL_WAIT.isBound()) {
      POOL_WAIT.get().add(nanos);
    }
  }
}
