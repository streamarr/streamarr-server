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

  public LoginThrottle(AuthThrottleProperties properties, Clock clock) {
    budget = new SlidingWindowAttemptBudget<>(properties.maxAttempts(), properties.window(), clock);
  }

  /** Reserves one email slot or throws; source exhaustion only raises the alerting signal. */
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
   * A successful login proves account ownership, so the email budget clears fully; the source
   * budget only releases this attempt's own slot — one success must not vouch away a source's
   * accumulated failures against other accounts.
   */
  public void reset(String email, String source) {
    budget.reset(emailKey(email));
    budget.release(sourceKey(source));
  }

  /**
   * Drops entries whose attempts all fell out of the window. Without this, unique sprayed keys
   * would accumulate forever — they are never touched again, so per-touch pruning cannot reach
   * them. Returns the number of evicted entries for observability.
   */
  public int sweepExpired() {
    return budget.sweepExpired();
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
