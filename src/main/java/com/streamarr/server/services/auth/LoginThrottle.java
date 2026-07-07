package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import java.time.Clock;
import java.time.Instant;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * In-memory, per-instance login throttle keyed by account email and request source. Restart resets
 * the counters and N instances multiply the attempt budget by N — the same single-JVM posture as
 * MutexFactory; database-backed throttling is the documented fast-follow if multi-instance
 * deployment materialises.
 */
@Component
@RequiredArgsConstructor
public class LoginThrottle {

  private final AuthThrottleProperties properties;
  private final Clock clock;

  private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

  public void ensureAllowed(String email, String source) {
    if (isBlocked(emailKey(email)) || isBlocked(sourceKey(source))) {
      throw new TooManyLoginAttemptsException();
    }
  }

  public void recordFailure(String email, String source) {
    record(emailKey(email));
    record(sourceKey(source));
  }

  public void reset(String email, String source) {
    if (emailKey(email) != null) {
      failures.remove(emailKey(email));
    }
    if (sourceKey(source) != null) {
      failures.remove(sourceKey(source));
    }
  }

  private boolean isBlocked(String key) {
    if (key == null) {
      return false;
    }

    var attempts = failures.get(key);
    if (attempts == null) {
      return false;
    }

    prune(attempts);
    return attempts.size() >= properties.maxAttempts();
  }

  private void record(String key) {
    if (key == null) {
      return;
    }

    var attempts = failures.computeIfAbsent(key, _ -> new ConcurrentLinkedDeque<>());
    attempts.addLast(clock.instant());
    prune(attempts);
  }

  private void prune(Deque<Instant> attempts) {
    var cutoff = clock.instant().minus(properties.window());
    while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
      attempts.pollFirst();
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
