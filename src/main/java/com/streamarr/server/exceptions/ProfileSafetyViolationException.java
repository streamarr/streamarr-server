package com.streamarr.server.exceptions;

import java.util.List;
import java.util.UUID;

public class ProfileSafetyViolationException extends RuntimeException {

  private final List<UUID> profilesRequiringPin;

  public ProfileSafetyViolationException(List<UUID> profilesRequiringPin) {
    super("One or more profiles require a PIN before this change can be applied.");
    this.profilesRequiringPin = List.copyOf(profilesRequiringPin);
  }

  public List<UUID> profilesRequiringPin() {
    return profilesRequiringPin;
  }
}
