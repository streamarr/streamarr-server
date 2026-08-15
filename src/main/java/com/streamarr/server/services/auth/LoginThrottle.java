package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import java.time.Clock;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-memory, per-instance login throttle keyed by account email and request source. An attempt
 * reserves its slot atomically before any password work, so a concurrent burst cannot overrun the
 * budget; blocked attempts reserve nothing, so hostile traffic cannot extend a victim's lockout.
 *
 * <p>The email budget is the hard limit. The source budget is an alerting signal only: behind a
 * reverse proxy — the normal self-hosted deployment — every client shares the proxy's address, so a
 * blocking source budget would let five failed logins lock every account out server-wide. Exhausted
 * sources log a WARN operators can alert on; forwarded-header resolution
 * (SERVER_FORWARD_HEADERS_STRATEGY) is opt-in for proxied deployments that want real client
 * addresses in that signal.
 *
 * <p>Restart resets the counters and N instances multiply the attempt budget by N — the same
 * single-JVM posture as MutexFactory; database-backed throttling is the fast-follow if
 * multi-instance deployment materialises.
 */
@Slf4j
@Component
public class LoginThrottle {

  private final SlidingWindowAttemptBudget<String> budget;

  /**
   * Creates a login throttle using the configured attempt limit and time window.
   *
   * @param properties the login throttling configuration
   * @param clock      the clock used to measure the throttling window
   */
  public LoginThrottle(AuthThrottleProperties properties, Clock clock) {
    budget = new SlidingWindowAttemptBudget<>(properties.maxAttempts(), properties.window(), clock);
  }

  /**
   * Registers a login attempt for an account and its source.
   *
   * @param email  the account email associated with the attempt
   * @param source the source associated with the attempt
   * @throws TooManyLoginAttemptsException if the account's attempt budget is exhausted
   */
  public void registerAttempt(String email, String source) {
    var emailKey = emailKey(email);
    var sourceKey = sourceKey(source);

    if (!budget.reserve(emailKey)) {
      log.warn("Login throttled: attempt budget exhausted for {}", emailKey);
      throw new TooManyLoginAttemptsException();
    }
    if (!budget.reserve(sourceKey)) {
      log.warn(
          "Login pressure: source attempt budget exhausted for {} — attempts continue; the"
              + " per-account budget remains the hard limit",
          sourceKey);
    }
  }

  /**
   * Clears the account's login-attempt budget after successful authentication and releases the current source attempt.
   *
   * @param email  the account email whose budget is cleared
   * @param source the login source whose current attempt is released
   */
  public void reset(String email, String source) {
    budget.reset(emailKey(email));
    budget.release(sourceKey(source));
  }

  /**
   * Removes entries whose attempts have fully expired.
   *
   * @return the number of removed entries
   */
  public int sweepExpired() {
    return budget.sweepExpired();
  }

  /**
   * Normalizes an email address into a throttling key.
   *
   * @param email the email address to normalize
   * @return the lowercased email prefixed with {@code email:}, or {@code null} if the email is {@code null}
   */
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
