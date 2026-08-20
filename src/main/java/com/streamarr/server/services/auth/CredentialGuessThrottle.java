package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Guessing budgets for credentials an already-authenticated Account presents: its own password
 * (reauthentication, password change) and a Profile PIN. The two budgets are independent — a PIN
 * guess must not spend password attempts or vice versa — and both are keyed by Account, never by
 * source address, for the reason {@link LoginThrottle} records. Keys are bounded by Accounts and
 * Profiles, so there is nothing to spray.
 */
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

  /** One budget per presented publicId: rotating endpoints or sources must not multiply tries. */
  public void registerCodeGuess(String publicId) {
    register(
        new CredentialKey(
            CredentialType.OPAQUE_CODE,
            UUID.nameUUIDFromBytes(publicId.getBytes(StandardCharsets.UTF_8)),
            null));
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
        "Credential verification throttled: {} budget exhausted for account {}",
        key.type(),
        key.accountId());
    throw new TooManyCredentialAttemptsException();
  }

  private enum CredentialType {
    PROFILE_PIN,
    ACCOUNT_PASSWORD,
    OPAQUE_CODE
  }

  private record CredentialKey(CredentialType type, UUID accountId, UUID profileId) {}
}
