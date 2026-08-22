package com.streamarr.server.fixtures;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;

public final class ProfileFixture {

  private ProfileFixture() {}

  /** An unrestricted Adult Profile with no PIN. */
  public static Profile.ProfileBuilder<?, ?> defaultProfileBuilder() {
    return Profile.builder().name("Profile-" + UUID.randomUUID()).kind(ProfileKind.ADULT);
  }

  public static Profile.ProfileBuilder<?, ?> kidProfileBuilder() {
    return defaultProfileBuilder().kind(ProfileKind.KID);
  }
}
