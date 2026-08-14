package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record SetProfileContentCeilingCommand(
    UUID actingAccountId, UUID profileId, int maximumAllowedRatingAge) {

  public SetProfileContentCeilingCommand {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(profileId, "profileId");
    if (maximumAllowedRatingAge < 0) {
      throw new IllegalArgumentException("maximumAllowedRatingAge must not be negative");
    }
  }
}
