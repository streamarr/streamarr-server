package com.streamarr.server.services.library.admission;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One admitted item, from admission until it finishes. Counts toward {@code poc.scan.inflight}
 * from {@link AdmissionRuntime#admit()} until it is finished, and toward {@link
 * AdmissionRuntime#inTransit()} until its first TMDB gate reservation or its end, whichever comes
 * first.
 *
 * <p>Simple strategies call {@link #run}. A strategy that calls stages separately wraps each stage
 * in {@link #within} (so the TMDB signals reach this ticket from whichever thread runs the stage)
 * and ends the ticket with {@link #finish} or {@link #fail} exactly once.
 */
public final class AdmissionTicket {

  private final AdmissionRuntime runtime;
  private final long admittedAtNanos = System.nanoTime();
  private final AtomicBoolean tmdbRetried = new AtomicBoolean();
  private final AtomicInteger tmdbApiReservations = new AtomicInteger();
  private final AtomicInteger tmdbImageReservations = new AtomicInteger();
  private final AtomicLong tmdbGateWaitNanos = new AtomicLong();
  private final AtomicBoolean leftTransit = new AtomicBoolean();
  private final AtomicBoolean ended = new AtomicBoolean();

  AdmissionTicket(AdmissionRuntime runtime) {
    this.runtime = runtime;
  }

  /** Runs the whole task inside this ticket's scope and ends the ticket with its outcome. */
  public <T> TaskOutcome run(AdmittedTask<T> task, T item) throws Exception {
    try {
      var outcome = within(() -> task.run(item));
      finish(outcome);
      return outcome;
    } catch (Throwable failure) {
      fail();
      throw failure;
    }
  }

  /** Runs one piece of the task's work with the per-task TMDB signals bound to this ticket. */
  public <V> V within(ScopedValue.CallableOp<V, Exception> work) throws Exception {
    return ScopedValue.where(TmdbTaskSignals.TMDB_RETRIED, tmdbRetried)
        .where(TmdbTaskSignals.TICKET, this)
        .call(work);
  }

  public void finish(TaskOutcome outcome) {
    if (ended.compareAndSet(false, true)) {
      leaveTransit();
      runtime.completed(this, outcome);
    }
  }

  public void fail() {
    if (ended.compareAndSet(false, true)) {
      leaveTransit();
      runtime.failed(this);
    }
  }

  void onTmdbReservation(boolean image, long waitNanos) {
    tmdbGateWaitNanos.addAndGet(waitNanos);
    if (image) {
      tmdbImageReservations.incrementAndGet();
    } else {
      tmdbApiReservations.incrementAndGet();
    }

    leaveTransit();
  }

  private void leaveTransit() {
    if (leftTransit.compareAndSet(false, true)) {
      runtime.leftTransit(this);
    }
  }

  /** True once a TMDB request made by this task was retried after a 429. */
  public boolean tmdbRetried() {
    return tmdbRetried.get();
  }

  /** TMDB API gate reservations made by this task so far (retries included). */
  public int tmdbApiReservations() {
    return tmdbApiReservations.get();
  }

  /** TMDB image gate reservations made on this task's thread (normally 0: artwork is async). */
  public int tmdbImageReservations() {
    return tmdbImageReservations.get();
  }

  /** Total time this task's TMDB requests waited for their gate slots. */
  public long tmdbGateWaitNanos() {
    return tmdbGateWaitNanos.get();
  }

  /** True once this task reached the TMDB gate or ended; false while admitted but in transit. */
  public boolean leftTransit() {
    return leftTransit.get();
  }

  public boolean ended() {
    return ended.get();
  }

  public long admittedAtNanos() {
    return admittedAtNanos;
  }
}
