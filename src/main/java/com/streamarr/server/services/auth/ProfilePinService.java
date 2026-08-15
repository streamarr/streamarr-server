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

  /**
   * Validates and encodes a profile PIN.
   *
   * @param pin the PIN to validate and encode
   * @return the encoded PIN
   * @throws InvalidProfilePinException if the PIN is null or does not contain 4–12 digits
   */
  public String encode(String pin) {
    if (pin == null || !VALID_PIN.matcher(pin).matches()) {
      throw new InvalidProfilePinException();
    }
    return passwordEncoder.encode(pin);
  }

  /**
   * Ensures that the supplied PIN grants access to the profile.
   *
   * @param profile the profile whose PIN configuration determines whether entry is required
   * @param pin the PIN supplied for access
   * @throws ProfileAccessDeniedException if the profile requires a PIN and the supplied PIN is missing or incorrect
   */
  public void requireEntry(Profile profile, String pin) {
    if (!requiresEntry(profile)) {
      return;
    }
    if (pin != null && matches(pin, profile)) {
      return;
    }
    throw new ProfileAccessDeniedException();
  }

  /**
   * Determines whether the profile has a configured PIN.
   *
   * @param profile the profile to inspect
   * @return {@code true} if the profile has a nonblank PIN hash, {@code false} otherwise
   */
  public boolean requiresEntry(Profile profile) {
    return profile.getPinHash() != null && !profile.getPinHash().isBlank();
  }

  /**
   * Determines whether a supplied PIN matches the profile's stored PIN hash.
   *
   * @param pin     the PIN to verify
   * @param profile the profile containing the stored PIN hash
   * @return {@code true} if the PIN matches the stored hash, {@code false} otherwise
   */
  private boolean matches(String pin, Profile profile) {
    try {
      return passwordEncoder.matches(pin, profile.getPinHash());
    } catch (IllegalArgumentException _) {
      log.error("Stored PIN hash for profile {} is unreadable.", profile.getId());
      return false;
    }
  }
}
