package com.streamarr.server.fixtures;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;

public final class ProfileHouseholdShareFixture {

  private ProfileHouseholdShareFixture() {}

  public static ProfileHouseholdShare.ProfileHouseholdShareBuilder<?, ?> activeShareBuilder() {
    return ProfileHouseholdShare.builder().status(ProfileShareStatus.ACTIVE).structural(false);
  }
}
