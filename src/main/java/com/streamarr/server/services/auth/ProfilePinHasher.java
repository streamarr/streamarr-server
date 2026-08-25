package com.streamarr.server.services.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hashes Profile PINs on the way in (ADR 0024 §Profile PIN): the raw PIN is consumed here, outside
 * any transaction, and only the hash travels further. Format is a product rule enforced
 * server-side: 4–8 digits, matching the PIN entry surfaces.
 */
@Component
@RequiredArgsConstructor
public class ProfilePinHasher {

  private static final int MINIMUM_PIN_LENGTH = 4;
  private static final int MAXIMUM_PIN_LENGTH = 8;

  private final PasswordEncoder passwordEncoder;

  public boolean isWellFormed(String pin) {
    if (pin == null || pin.length() < MINIMUM_PIN_LENGTH || pin.length() > MAXIMUM_PIN_LENGTH) {
      return false;
    }

    return pin.chars().allMatch(character -> character >= '0' && character <= '9');
  }

  public String hash(String pin) {
    return passwordEncoder.encode(pin);
  }
}
