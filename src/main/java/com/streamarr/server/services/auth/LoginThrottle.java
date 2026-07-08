package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * In-memory, per-instance login throttle keyed by account email and request source. An attempt
 * reserves its slot atomically before any password work, so a concurrent burst cannot overrun the
 * budget; blocked attempts reserve nothing, so hostile traffic cannot extend a victim's lockout.
 * Restart resets the counters and N instances multiply the attempt budget by N — the same
 * single-JVM posture as MutexFactory; database-backed throttling is the documented fast-follow if
 * multi-instance deployment materialises.
 */
@Component
@RequiredArgsConstructor
public class LoginThrottle {

  private final AuthThrottleProperties properties;
  private final Clock clock;

  private final ConcurrentHashMap<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

  /** Reserves one attempt slot on both keys, or throws without consuming any budget. */
  public void registerAttempt(String email, String source) {
    var emailKey = emailKey(email);
    var sourceKey = sourceKey(source);

    if (!reserve(emailKey)) {
      throw new TooManyLoginAttemptsException();
    }
    if (reserve(sourceKey)) {
      return;
    }

    release(emailKey);
    throw new TooManyLoginAttemptsException();
  }

  /**
   * A successful login proves account ownership, so the email budget clears fully; the source
   * budget only releases this attempt's own slot — one success must not vouch away a source's
   * accumulated failures against other accounts.
   */
  public void reset(String email, String source) {
    removeKey(emailKey(email));
    release(sourceKey(source));
  }

  /**
   * Drops entries whose attempts all fell out of the window. Without this, unique sprayed keys
   * would accumulate forever — they are never touched again, so per-touch pruning cannot reach
   * them. Returns the number of evicted entries for observability.
   */
  public int sweepExpired() {
    var evicted = 0;
    for (var key : attempts.keySet()) {
      var remaining = attempts.computeIfPresent(key, (_, timestamps) -> pruned(timestamps));
      if (remaining == null) {
        evicted++;
      }
    }
    return evicted;
  }

  private boolean reserve(String key) {
    if (key == null) {
      return true;
    }

    var reserved = new AtomicBoolean();
    attempts.compute(
        key,
        (_, timestamps) -> {
          var current = timestamps == null ? new ArrayDeque<Instant>() : timestamps;
          prune(current);
          if (current.size() < properties.maxAttempts()) {
            current.addLast(clock.instant());
            reserved.set(true);
          }
          // Never empty here: blocked means at least maxAttempts entries, reserved means one
          // was just added.
          return current;
        });
    return reserved.get();
  }

  /**
   * Returns one reserved slot. Timestamps within the window are fungible — the deque is an expiring
   * counter, not a set of identified reservations — so removing the newest entry on behalf of an
   * older reservation keeps the observable budget (count per window) exactly right under any
   * interleaving; only the retained entry's expiry shifts, by the microseconds between the
   * concurrent appends.
   */
  private void release(String key) {
    if (key == null) {
      return;
    }

    attempts.computeIfPresent(
        key,
        (_, timestamps) -> {
          timestamps.pollLast();
          return timestamps.isEmpty() ? null : timestamps;
        });
  }

  private void removeKey(String key) {
    if (key == null) {
      return;
    }
    attempts.remove(key);
  }

  private Deque<Instant> pruned(Deque<Instant> timestamps) {
    prune(timestamps);
    return timestamps.isEmpty() ? null : timestamps;
  }

  private void prune(Deque<Instant> timestamps) {
    var cutoff = clock.instant().minus(properties.window());
    while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
      timestamps.pollFirst();
    }
  }

  private static String emailKey(String email) {
    if (email == null) {
      return null;
    }
    return "email:" + email.toLowerCase(Locale.ROOT);
  }

  private static String sourceKey(String source) {
    if (source == null) {
      return null;
    }
    return "src:" + source;
  }
}
