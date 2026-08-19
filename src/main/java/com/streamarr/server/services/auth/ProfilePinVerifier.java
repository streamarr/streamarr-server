package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.exceptions.InvalidProfilePinException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The single PIN checkpoint (ADR 0024): PIN verification exists only inside the select-profile
 * ceremony, throttled on the PROFILE_PIN budget keyed by Account and Profile. The verified result
 * is trusted attempt context the selection service alone creates; no client supplies it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfilePinVerifier {

  private final PasswordEncoder passwordEncoder;
  private final CredentialGuessThrottle throttle;

  /**
   * @throws com.streamarr.server.exceptions.TooManyCredentialAttemptsException when the budget is
   *     exhausted — before any hashing
   * @throws InvalidProfilePinException when the PIN is missing or does not match
   */
  public void verify(UUID accountId, Profile profile, String pin) {
    throttle.registerProfilePinAttempt(accountId, profile.getId());
    if (pin == null || pin.isBlank() || !matches(profile, pin)) {
      throw new InvalidProfilePinException();
    }
    throttle.resetProfilePinAttempts(accountId, profile.getId());
  }

  private boolean matches(Profile profile, String pin) {
    try {
      return passwordEncoder.matches(pin, profile.getPinHash());
    } catch (IllegalArgumentException e) {
      log.error("Stored PIN hash for profile {} is unreadable.", profile.getId(), e);
      return false;
    }
  }
}
