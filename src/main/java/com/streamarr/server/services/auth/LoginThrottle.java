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

  public void reset(String email, String source) {
    removeKey(emailKey(email));
    removeKey(sourceKey(source));
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
          return current.isEmpty() ? null : current;
        });
    return reserved.get();
  }

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
