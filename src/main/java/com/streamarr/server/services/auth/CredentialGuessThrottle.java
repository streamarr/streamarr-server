package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import java.time.Clock;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Guessing budgets for the credentials a request presents beyond login. Authenticated: an Account's
 * own password (reauthentication, password change) and a Profile PIN, independent of each other and
 * keyed by Account, never by source address, for the reason {@link LoginThrottle} records; those
 * keys are naturally bounded. Principal-less: opaque one-time codes, keyed by the presented
 * publicId (which must already exist, so only issued codes take a slot) and capped at {@code
 * maxOpaqueCodeBudgets} tracked keys, refusing new keys at capacity rather than growing.
 */
@Slf4j
@Component
public class CredentialGuessThrottle {

  private final SlidingWindowAttemptBudget<CredentialKey> credentialBudget;
  private final SlidingWindowAttemptBudget<String> opaqueCodeBudget;

  public CredentialGuessThrottle(AuthThrottleProperties properties, Clock clock) {
    credentialBudget =
        new SlidingWindowAttemptBudget<>(
            SlidingWindowAttemptBudget.Limits.unboundedKeys(
                properties.maxAttempts(), properties.window()),
            clock);
    opaqueCodeBudget =
        new SlidingWindowAttemptBudget<>(
            SlidingWindowAttemptBudget.Limits.builder()
                .maximumAttempts(properties.maxAttempts())
                .window(properties.window())
                .maximumTrackedKeys(properties.maxOpaqueCodeBudgets())
                .build(),
            clock);
  }

  public void registerProfilePinAttempt(UUID accountId, UUID profileId) {
    register(new CredentialKey(CredentialType.PROFILE_PIN, accountId, profileId));
  }

  public void resetProfilePinAttempts(UUID accountId, UUID profileId) {
    credentialBudget.reset(new CredentialKey(CredentialType.PROFILE_PIN, accountId, profileId));
  }

  /** One budget per presented publicId: rotating endpoints or sources must not multiply tries. */
  public void registerCodeGuess(String publicId) {
    requireAvailable(opaqueCodeBudget.reserve(publicId), CredentialType.OPAQUE_CODE, publicId);
  }

  public void resetCodeGuesses(String publicId) {
    opaqueCodeBudget.reset(publicId);
  }

  public void registerAccountPasswordAttempt(UUID accountId) {
    register(new CredentialKey(CredentialType.ACCOUNT_PASSWORD, accountId, null));
  }

  public void resetAccountPasswordAttempts(UUID accountId) {
    credentialBudget.reset(new CredentialKey(CredentialType.ACCOUNT_PASSWORD, accountId, null));
  }

  public int sweepExpired() {
    return credentialBudget.sweepExpired() + opaqueCodeBudget.sweepExpired();
  }

  private void register(CredentialKey key) {
    requireAvailable(credentialBudget.reserve(key), key.type(), key.accountId());
  }

  private void requireAvailable(
      SlidingWindowAttemptBudget.Reservation reservation, CredentialType type, Object key) {
    var available =
        switch (reservation) {
          case RESERVED -> true;
          case KEY_EXHAUSTED -> {
            log.warn(
                "Credential verification throttled: {} budget exhausted for key {}", type, key);
            yield false;
          }
          case CAPACITY_EXHAUSTED -> {
            log.warn(
                "Credential verification refused: {} throttle at capacity ({} keys tracked); new"
                    + " codes wait for a reset or the sweep",
                type,
                opaqueCodeBudget.maximumTrackedKeys());
            yield false;
          }
        };
    if (available) {
      return;
    }

    throw new TooManyCredentialAttemptsException();
  }

  private enum CredentialType {
    PROFILE_PIN,
    ACCOUNT_PASSWORD,
    OPAQUE_CODE
  }

  private record CredentialKey(CredentialType type, UUID accountId, UUID profileId) {}
}
