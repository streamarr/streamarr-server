package com.streamarr.server.domain.auth;

/**
 * What an Account is to a Profile (ADR 0024): one of its ProfileManagers, or a HouseholdAdmin who
 * supervises it while the restricted Profile is available in the Account's Household.
 */
public enum ProfileAuthority {
  MANAGES,
  SUPERVISES
}
