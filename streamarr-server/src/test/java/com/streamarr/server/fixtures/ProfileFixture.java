package com.streamarr.server.fixtures;

import com.streamarr.server.domain.auth.Profile;
import java.util.UUID;

public final class ProfileFixture {

  private ProfileFixture() {}

  public static Profile.ProfileBuilder<?, ?> defaultProfileBuilder() {
    return Profile.builder().name("Profile-" + UUID.randomUUID());
  }
}
