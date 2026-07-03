package com.streamarr.server.graphql;

import java.util.UUID;

public final class CurrentUser {

  private static final UUID PLACEHOLDER_USER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private CurrentUser() {}

  // TODO(#163): Replace with authenticated user ID from Spring Security
  public static UUID id() {
    return PLACEHOLDER_USER_ID;
  }
}
