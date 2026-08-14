package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import java.util.Objects;

public record ProfileHouseholdShareInsertResult(ProfileHouseholdShare share, boolean inserted) {

  public ProfileHouseholdShareInsertResult {
    Objects.requireNonNull(share);
  }
}
