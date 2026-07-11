package com.streamarr.server.support.security;

import java.util.UUID;

/**
 * Fixed ids for annotation-driven resolver tests. Deliberately NOT the retired placeholder id
 * (00000000-…-0001) — anything still assuming the placeholder must fail loudly.
 */
public final class TestIdentityConstants {

  public static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  public static final UUID HOUSEHOLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  public static final UUID PROFILE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
  public static final UUID SESSION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

  private TestIdentityConstants() {}
}
