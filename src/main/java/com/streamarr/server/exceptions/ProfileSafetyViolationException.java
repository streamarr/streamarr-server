package com.streamarr.server.exceptions;

import java.util.List;
import java.util.UUID;

public class ProfileSafetyViolationException extends RuntimeException {

  private final List<UUID> profilesRequiringPin;

  /**
   * Creates an exception for a change that requires PIN verification for one or more profiles.
   *
   * @param profilesRequiringPin the profiles requiring PIN verification
   */
  public ProfileSafetyViolationException(List<UUID> profilesRequiringPin) {
    super("One or more profiles require a PIN before this change can be applied.");
    this.profilesRequiringPin = List.copyOf(profilesRequiringPin);
  }

  /**
   * Identifies the profiles requiring PIN verification.
   *
   * @return the UUIDs of profiles requiring PIN verification
   */
  public List<UUID> profilesRequiringPin() {
    return profilesRequiringPin;
  }
}
