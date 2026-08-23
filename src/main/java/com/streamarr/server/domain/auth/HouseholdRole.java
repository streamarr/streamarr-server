package com.streamarr.server.domain.auth;

/**
 * An Account's role in its one membership Household (ADR 0024): peer HouseholdAdmins or members.
 */
public enum HouseholdRole {
  ADMIN,
  MEMBER
}
