package com.streamarr.server.services.auth;

import java.util.UUID;

/**
 * The scope ADR 0016 auto-selection arrives at. It follows from the account alone, so it can be
 * resolved before a session exists to carry it.
 */
public record AutoSelection(UUID householdId, UUID profileId) {

  private static final AutoSelection NONE = new AutoSelection(null, null);

  public AutoSelection {
    if (profileId != null && householdId == null) {
      throw new IllegalArgumentException("A selected profile requires its household.");
    }
  }

  public static AutoSelection none() {
    return NONE;
  }

  public static AutoSelection household(UUID householdId) {
    return new AutoSelection(householdId, null);
  }

  public static AutoSelection householdAndProfile(UUID householdId, UUID profileId) {
    return new AutoSelection(householdId, profileId);
  }

  public boolean hasHousehold() {
    return householdId != null;
  }

  public boolean hasProfile() {
    return profileId != null;
  }
}
