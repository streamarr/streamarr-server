package com.streamarr.server.services.auth;

/** Signed snapshot claims (ADR 0024): facts, never computed permissions. */
public final class TokenClaims {

  public static final String SESSION_ID = "sid";
  public static final String SCOPE = "scope";

  /** The Account's membership Household and its role there. */
  public static final String HOUSEHOLD_ID = "hh";

  public static final String HOUSEHOLD_ROLE = "hr";

  /** ServerAdmin display snapshot — routing and UI only, never authority. */
  public static final String SERVER_ADMIN = "sa";

  /** The one context Household this session is using. */
  public static final String CONTEXT_HOUSEHOLD_ID = "ch";

  public static final String PROFILE_ID = "pf";

  /**
   * When this token's bearer last passed the reauthentication ceremony (ADR 0024 §Fresh
   * reauthentication). Present only on a token issued by POST /api/auth/reauth or derived from one;
   * refresh removes it.
   */
  public static final String REAUTHENTICATED_AT = "reauthenticated_at";

  public static final String STREAM_SESSION_ID = "stream_session_id";

  private TokenClaims() {}
}
