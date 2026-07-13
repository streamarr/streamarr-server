package com.streamarr.server.domain.streaming;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record PlaybackAuthority(
    UUID authSessionId, UUID accountId, UUID householdId, UUID profileId) {

  public PlaybackAuthority {
    Objects.requireNonNull(authSessionId, "authSessionId is required");
    Objects.requireNonNull(accountId, "accountId is required");
    Objects.requireNonNull(householdId, "householdId is required");
    Objects.requireNonNull(profileId, "profileId is required");
  }
}
