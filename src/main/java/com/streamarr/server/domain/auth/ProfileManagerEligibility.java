package com.streamarr.server.domain.auth;

/**
 * Who may be named as a Profile's direct manager (ADR 0024): any member of the Profile's home
 * Household whose own Personal Profile is unrestricted, or additionally a HouseholdAdmin when the
 * managed Profile is restricted.
 */
public enum ProfileManagerEligibility {
  HOUSEHOLD_MEMBER,
  HOUSEHOLD_ADMIN;

  public static ProfileManagerEligibility forRestricted(boolean restricted) {
    if (restricted) {
      return HOUSEHOLD_ADMIN;
    }

    return HOUSEHOLD_MEMBER;
  }
}
