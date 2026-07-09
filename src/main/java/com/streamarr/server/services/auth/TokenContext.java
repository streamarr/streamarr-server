package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.UserAccount;
import java.util.UUID;
import lombok.Builder;

@Builder
public record TokenContext(
    UserAccount account, AuthSession session, UUID householdId, UUID profileId) {

  public TokenContext {
    if (profileId != null && householdId == null) {
      throw new IllegalArgumentException("Profile context requires a household");
    }
  }
}
