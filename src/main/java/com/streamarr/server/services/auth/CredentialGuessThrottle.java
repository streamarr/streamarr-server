package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import java.time.Clock;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CredentialGuessThrottle {

  private final SlidingWindowAttemptBudget<CredentialKey> budget;

  public CredentialGuessThrottle(AuthThrottleProperties properties, Clock clock) {
    budget = new SlidingWindowAttemptBudget<>(properties.maxAttempts(), properties.window(), clock);
  }

  public void registerProfilePinAttempt(UUID accountId, UUID profileId) {
    register(new CredentialKey(CredentialType.PROFILE_PIN, accountId, profileId));
  }

  public void resetProfilePinAttempts(UUID accountId, UUID profileId) {
    budget.reset(new CredentialKey(CredentialType.PROFILE_PIN, accountId, profileId));
  }

  public void registerAccountPasswordAttempt(UUID accountId) {
    register(new CredentialKey(CredentialType.ACCOUNT_PASSWORD, accountId, null));
  }

  public void resetAccountPasswordAttempts(UUID accountId) {
    budget.reset(new CredentialKey(CredentialType.ACCOUNT_PASSWORD, accountId, null));
  }

  public int sweepExpired() {
    return budget.sweepExpired();
  }

  private void register(CredentialKey key) {
    if (budget.reserve(key)) {
      return;
    }
    log.warn(
        "Credential verification throttled for account {} and type {}",
        key.accountId(),
        key.type());
    throw new TooManyCredentialAttemptsException();
  }

  private enum CredentialType {
    PROFILE_PIN,
    ACCOUNT_PASSWORD
  }

  private record CredentialKey(CredentialType type, UUID accountId, UUID profileId) {}
}
