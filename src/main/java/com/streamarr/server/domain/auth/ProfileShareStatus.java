package com.streamarr.server.domain.auth;

/** Lifecycle of one Profile's share into one Household; only PENDING and ACTIVE are live. */
public enum ProfileShareStatus {
  PENDING,
  ACTIVE,
  REJECTED,
  CANCELED,
  EXPIRED,
  INVALIDATED,
  ENDED;

  /** Whether the offer is still pending or the share is active; every other status is over. */
  public boolean isLive() {
    return this == PENDING || this == ACTIVE;
  }
}
