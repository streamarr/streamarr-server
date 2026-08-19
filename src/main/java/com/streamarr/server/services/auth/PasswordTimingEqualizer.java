package com.streamarr.server.services.auth;

import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * One shared full-cost Argon2 burn for every password rejection path that performs no real
 * comparison — unknown email, disabled Account, unreadable stored hash — so response timing cannot
 * disclose which of those happened. The equalizer hash is encoded once at startup with the live
 * encoder parameters, so a burn costs exactly what a real comparison costs.
 */
@Component
public class PasswordTimingEqualizer {

  private final PasswordEncoder passwordEncoder;
  private final String equalizerHash;

  public PasswordTimingEqualizer(PasswordEncoder passwordEncoder) {
    this.passwordEncoder = passwordEncoder;
    this.equalizerHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  /** Runs one full-cost comparison against the equalizer hash; the result is discarded. */
  public void burn(String password) {
    passwordEncoder.matches(password, equalizerHash);
  }
}
