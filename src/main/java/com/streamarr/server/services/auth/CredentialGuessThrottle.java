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

  /**
   * Creates a credential-attempt throttle using the configured attempt budget and time source.
   *
   * @param properties the authentication throttling configuration
   * @param clock      the clock used to determine the sliding-window time
   */
  public CredentialGuessThrottle(AuthThrottleProperties properties, Clock clock) {
    budget = new SlidingWindowAttemptBudget<>(properties.maxAttempts(), properties.window(), clock);
  }

  /**
   * Registers a profile PIN verification attempt for an account and profile.
   *
   * @param accountId  the account identifier
   * @param profileId  the profile identifier
   */
  public void registerProfilePinAttempt(UUID accountId, UUID profileId) {
    register(new CredentialKey(CredentialType.PROFILE_PIN, accountId, profileId));
  }

  /**
   * Clears the recorded profile PIN attempts for an account and profile.
   *
   * @param accountId the account whose attempts are cleared
   * @param profileId the profile whose attempts are cleared
   */
  public void resetProfilePinAttempts(UUID accountId, UUID profileId) {
    budget.reset(new CredentialKey(CredentialType.PROFILE_PIN, accountId, profileId));
  }

  /**
   * Registers a server administrator password verification attempt for an account.
   *
   * @param accountId the account associated with the password attempt
   */
  public void registerServerAdminPasswordAttempt(UUID accountId) {
    register(new CredentialKey(CredentialType.SERVER_ADMIN_PASSWORD, accountId, null));
  }

  /**
   * Clears the server administrator password attempt budget for an account.
   *
   * @param accountId the account whose attempt budget is cleared
   */
  public void resetServerAdminPasswordAttempts(UUID accountId) {
    budget.reset(new CredentialKey(CredentialType.SERVER_ADMIN_PASSWORD, accountId, null));
  }

  /**
   * Removes expired credential-attempt budgets.
   *
   * @return the number of expired budgets removed
   */
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
    SERVER_ADMIN_PASSWORD
  }

  private record CredentialKey(CredentialType type, UUID accountId, UUID profileId) {}
}
