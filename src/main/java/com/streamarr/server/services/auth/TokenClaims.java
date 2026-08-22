package com.streamarr.server.services.auth;

/** Signed snapshot claims (ADR 0024): facts, never computed permissions. */
public final class TokenClaims {

  public static final String SESSION_ID = "sid";
  public static final String SCOPE = "scope";

  /** The Account's membership Household and its role there. */
  public static final String HOUSEHOLD_ID = "hh";

  public static final String HOUSEHOLD_ROLE = "hr";

  /** The one context Household this session is using. */
  public static final String CONTEXT_HOUSEHOLD_ID = "ch";

  public static final String PROFILE_ID = "pf";
  public static final String STREAM_SESSION_ID = "stream_session_id";

  private TokenClaims() {}
}
