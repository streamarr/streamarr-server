package com.streamarr.server.domain.auth;

import java.util.UUID;

/** The policy-relevant state of a Profile at one read: kind, ceiling, and Account linkage. */
public record ProfilePolicySnapshot(
    ProfileKind kind, Integer maximumAllowedRatingAge, UUID linkedAccountId) {

  public boolean restricted() {
    return Profile.isRestricted(kind, maximumAllowedRatingAge);
  }

  public boolean linked() {
    return linkedAccountId != null;
  }
}
