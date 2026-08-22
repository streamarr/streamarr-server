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

  private static final String PIN_FORMAT = "\\d{4,8}";

  private final PasswordEncoder passwordEncoder;

  public boolean isWellFormed(String pin) {
    return pin != null && pin.matches(PIN_FORMAT);
  }

  public String hash(String pin) {
    return passwordEncoder.encode(pin);
  }
}
