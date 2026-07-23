package com.streamarr.server.services.auth;

public final class TokenClaims {

  // IANA-registered claim (RFC 7643 section 4.1.2, adopted by RFC 9068); JSON array value.
  public static final String ROLES = "roles";
  public static final String SESSION_ID = "sid";
  public static final String SCOPE = "scope";
  public static final String HOUSEHOLD_ID = "hh";
  public static final String HOUSEHOLD_ROLE = "hr";
  public static final String PROFILE_ID = "pf";
  public static final String STREAM_SESSION_ID = "stream_session_id";

  private TokenClaims() {}
}
