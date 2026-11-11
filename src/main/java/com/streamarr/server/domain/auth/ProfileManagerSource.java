package com.streamarr.server.domain.auth;

/** Where a ProfileManager's authority over a Profile comes from (ADR 0024 §ProfileManager). */
public enum ProfileManagerSource {
  /** The Profile is the Account's own unrestricted Adult Personal Profile. */
  DERIVED,
  /** The Account holds a direct grant for the Profile. */
  DIRECT
}
