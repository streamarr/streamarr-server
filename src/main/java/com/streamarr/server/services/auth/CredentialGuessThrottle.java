package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import java.time.Clock;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Guessing budget for an authenticated Account presenting its own password. The budget is keyed by
 * Account, never by source address, for the reason {@link LoginThrottle} records. Keys are bounded
 * by Accounts, so there is nothing to spray.
 */
@Slf4j
@Component
public class CredentialGuessThrottle {

  private final SlidingWindowAttemptBudget<UUID> budget;

  public CredentialGuessThrottle(AuthThrottleProperties properties, Clock clock) {
    budget = new SlidingWindowAttemptBudget<>(properties.maxAttempts(), properties.window(), clock);
  }

  public void registerAccountPasswordAttempt(UUID accountId) {
    register(accountId);
  }

  public void resetAccountPasswordAttempts(UUID accountId) {
    budget.reset(accountId);
  }

  public int sweepExpired() {
    return budget.sweepExpired();
  }

  private void register(UUID accountId) {
    if (budget.reserve(accountId)) {
      return;
    }
    log.warn(
        "Credential verification throttled: Account password budget exhausted for account {}",
        accountId);
    throw new TooManyCredentialAttemptsException();
  }
}
