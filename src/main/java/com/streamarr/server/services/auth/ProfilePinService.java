package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.exceptions.InvalidProfilePinException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilePinService {

  private static final Pattern VALID_PIN = Pattern.compile("\\d{4,12}");

  private final PasswordEncoder passwordEncoder;

  public String encode(String pin) {
    if (pin == null || !VALID_PIN.matcher(pin).matches()) {
      throw new InvalidProfilePinException();
    }
    return passwordEncoder.encode(pin);
  }

  public void requireEntry(Profile profile, String pin) {
    if (!hasEffectivePin(profile)) {
      return;
    }
    if (pin != null && matches(pin, profile)) {
      return;
    }
    throw new ProfileAccessDeniedException();
  }

  private boolean hasEffectivePin(Profile profile) {
    return profile.getPinHash() != null && !profile.getPinHash().isBlank();
  }

  private boolean matches(String pin, Profile profile) {
    try {
      return passwordEncoder.matches(pin, profile.getPinHash());
    } catch (IllegalArgumentException _) {
      log.error("Stored PIN hash for profile {} is unreadable.", profile.getId());
      return false;
    }
  }
}
