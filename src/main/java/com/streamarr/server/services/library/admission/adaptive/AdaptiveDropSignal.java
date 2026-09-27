package com.streamarr.server.services.library.admission.adaptive;

import java.net.http.HttpTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-task "an external limit pushed back" flag for the adaptive admission.
 *
 * <p>The metadata path catches TMDB timeouts ({@code TmdbSearchDelegate} and {@code
 * TMDBMovieProvider} turn every {@code IOException} into an outcome) and database failures during
 * enrichment and refresh, so the task's own result cannot show them. The TMDB client and the
 * connection pool are therefore observed where the exception is thrown, on the task's own thread,
 * and this flag carries the observation back to the task that owns the permit. Work handed to
 * another executor (artwork) is not bound and is not attributed.
 */
public final class AdaptiveDropSignal {

  static final ScopedValue<AtomicBoolean> DROPPED = ScopedValue.newInstance();

  private static final int MAX_CAUSE_DEPTH = 32;

  private AdaptiveDropSignal() {}

  /** Marks the admitted task running on this thread, if any, as dropped. */
  public static void markDropped() {
    if (DROPPED.isBound()) {
      DROPPED.get().set(true);
    }
  }

  /** True when {@code failure} or one of its causes is a TMDB timeout or a pool timeout. */
  static boolean isDropCause(Throwable failure) {
    var cause = failure;
    // Bounded: a cause chain can loop through initCause.
    for (var depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (cause instanceof HttpTimeoutException
          || cause instanceof SQLTransientConnectionException) {
        return true;
      }

      cause = cause.getCause();
    }

    return false;
  }
}
