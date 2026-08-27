package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.exceptions.InvalidProfilePinException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The single PIN checkpoint (ADR 0024): PIN verification exists only inside the select-profile
 * ceremony, limited by persisted PROFILE_PIN attempts keyed by Account and Profile. The result is
 * trusted attempt context the selection service alone creates; no client supplies it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfilePinVerifier {

  private final PasswordEncoder passwordEncoder;
  private final CredentialAttemptGate credentialAttempts;

  /**
   * @throws com.streamarr.server.exceptions.TooManyCredentialAttemptsException when the attempt
   *     limit is exhausted — before any hashing
   * @throws InvalidProfilePinException when the PIN is missing or does not match
   */
  public void verify(UUID accountId, Profile profile, String pin, String ipAddress) {
    // A missing PIN is transport-invalid input, not a guess: it spends no journal slot (ADR 0028).
    if (pin == null || pin.isBlank()) {
      throw new InvalidProfilePinException();
    }

    credentialAttempts.attempt(
        pinTarget(accountId, profile.getId(), ipAddress),
        () -> {
          if (!matches(profile, pin)) {
            throw new InvalidProfilePinException();
          }
        });
  }

  private static CredentialAttemptTarget pinTarget(
      UUID accountId, UUID profileId, String ipAddress) {
    return CredentialAttemptTarget.builder()
        .kind(CredentialKind.PROFILE_PIN)
        .accountId(accountId)
        .profileId(profileId)
        .ipAddress(ipAddress)
        .build();
  }

  private boolean matches(Profile profile, String pin) {
    try {
      return passwordEncoder.matches(pin, profile.getPinHash());
    } catch (IllegalArgumentException _) {
      log.error("Stored PIN hash for profile {} is unreadable.", profile.getId());
      return false;
    }
  }
}
