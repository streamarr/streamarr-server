package com.streamarr.server.services.streaming.remote;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Limits the warning about deferred probes to one for each refusal reason in each interval. Each
 * warning reports how many probes that reason deferred since the previous warning.
 */
final class ProbeDeferralWarnings {

  private final long intervalNanos;
  private final Map<ProbeRefusal, Window> windows = new EnumMap<>(ProbeRefusal.class);

  ProbeDeferralWarnings(Duration interval) {
    intervalNanos = interval.toNanos();
  }

  /**
   * Counts one deferred probe. Returns the probes deferred since the previous warning when a
   * warning is due, which starts a new interval.
   */
  synchronized Optional<Integer> tryTakeDeferredCount(ProbeRefusal reason, long nowNanos) {
    var window = windows.get(reason);
    if (window == null) {
      windows.put(reason, new Window(nowNanos, 0));
      return Optional.of(1);
    }

    var deferred = window.deferred() + 1;
    if (nowNanos - window.warnedAtNanos() < intervalNanos) {
      windows.put(reason, new Window(window.warnedAtNanos(), deferred));
      return Optional.empty();
    }

    windows.put(reason, new Window(nowNanos, 0));
    return Optional.of(deferred);
  }

  private record Window(long warnedAtNanos, int deferred) {}
}
