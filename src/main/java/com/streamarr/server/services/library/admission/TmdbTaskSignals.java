package com.streamarr.server.services.library.admission;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Per-task signals from the TMDB client back to the admitted task that made the request.
 *
 * <p>Both values are bound by {@link AdmissionTicket#within} on the task's own thread. The TMDB
 * client calls its interceptors on the calling thread for synchronous sends (the only kind the
 * metadata path makes), so the retry listener and the gate see the binding. Work that the task
 * hands to another executor (required and secondary artwork) is not bound and is not attributed.
 */
public final class TmdbTaskSignals {

  /** Set to true when a TMDB request made inside the bound task is retried after a 429. */
  public static final ScopedValue<AtomicBoolean> TMDB_RETRIED = ScopedValue.newInstance();

  /** The ticket of the admitted task running on this thread. */
  public static final ScopedValue<AdmissionTicket> TICKET = ScopedValue.newInstance();

  /**
   * True on a thread doing secondary (person and company) artwork. Inheritable, not scoped: the
   * artwork fetcher downloads each image on a new virtual thread of its own executor, which
   * inherits inheritable thread locals but not scoped values.
   */
  private static final InheritableThreadLocal<Boolean> SECONDARY_ARTWORK =
      new InheritableThreadLocal<>();

  private TmdbTaskSignals() {}

  /** Called by the TMDB retry listener before each retry. */
  public static void markRetried() {
    if (TMDB_RETRIED.isBound()) {
      TMDB_RETRIED.get().set(true);
    }
  }

  /** Called by the TMDB gate when a request reserves its slot, before it waits. */
  public static void markGateReservation(boolean image) {
    markGateReservation(image, 0);
  }

  /** As {@link #markGateReservation(boolean)}, with the wait the reservation got. */
  public static void markGateReservation(boolean image, long waitNanos) {
    if (TICKET.isBound()) {
      TICKET.get().onTmdbReservation(image, waitNanos);
    }
  }

  /** Runs secondary artwork work; TMDB requests it makes (on any thread it starts) are background. */
  public static <T> T callAsSecondaryArtwork(Supplier<T> work) {
    var previous = SECONDARY_ARTWORK.get();
    SECONDARY_ARTWORK.set(Boolean.TRUE);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        SECONDARY_ARTWORK.remove();
      } else {
        SECONDARY_ARTWORK.set(previous);
      }
    }
  }

  /** True when the current thread does secondary artwork work. */
  public static boolean isSecondaryArtwork() {
    return Boolean.TRUE.equals(SECONDARY_ARTWORK.get());
  }

  public static Optional<AdmissionTicket> currentTicket() {
    return TICKET.isBound() ? Optional.of(TICKET.get()) : Optional.empty();
  }
}
