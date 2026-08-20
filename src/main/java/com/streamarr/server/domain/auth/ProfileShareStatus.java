package com.streamarr.server.domain.auth;

/** Lifecycle of one Profile's share into one Household; only PENDING and ACTIVE are live. */
public enum ProfileShareStatus {
  PENDING,
  ACTIVE,
  REJECTED,
  CANCELED,
  EXPIRED,
  INVALIDATED,
  ENDED
}
