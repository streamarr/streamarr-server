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
 * source address, for the reason {@link LoginThrottle} records. Account and Profile keys are
 * naturally bounded; the separate unauthenticated opaque-code budget has an explicit key cap.
 */
@Slf4j
@Component
public class CredentialGuessThrottle {

  private final SlidingWindowAttemptBudget<CredentialKey> credentialBudget;
  private final SlidingWindowAttemptBudget<UUID> opaqueCodeBudget;

  public CredentialGuessThrottle(AuthThrottleProperties properties, Clock clock) {
    credentialBudget =
        new SlidingWindowAttemptBudget<>(properties.maxAttempts(), properties.window(), clock);
    opaqueCodeBudget =
        new SlidingWindowAttemptBudget<>(
            properties.maxAttempts(),
            properties.window(),
            clock,
            properties.maxOpaqueCodeBudgets());
  }

  public void registerProfilePinAttempt(UUID accountId, UUID profileId) {
    register(new CredentialKey(CredentialType.PROFILE_PIN, accountId, profileId));
  }

  public void resetProfilePinAttempts(UUID accountId, UUID profileId) {
    credentialBudget.reset(new CredentialKey(CredentialType.PROFILE_PIN, accountId, profileId));
  }

  /** One budget per presented publicId: rotating endpoints or sources must not multiply tries. */
  public void registerCodeGuess(String publicId) {
    var key = opaqueCodeKey(publicId);
    requireAvailable(opaqueCodeBudget.reserve(key), CredentialType.OPAQUE_CODE, key);
  }

  public void resetCodeGuesses(String publicId) {
    opaqueCodeBudget.reset(opaqueCodeKey(publicId));
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

  private void requireAvailable(boolean available, CredentialType type, UUID key) {
    if (available) {
      return;
    }

    log.warn("Credential verification throttled: {} budget exhausted for key {}", type, key);
    throw new TooManyCredentialAttemptsException();
  }

  private static UUID opaqueCodeKey(String publicId) {
    return UUID.nameUUIDFromBytes(publicId.getBytes(StandardCharsets.UTF_8));
  }

  private enum CredentialType {
    PROFILE_PIN,
    ACCOUNT_PASSWORD,
    OPAQUE_CODE
  }

  private record CredentialKey(CredentialType type, UUID accountId, UUID profileId) {}
}
