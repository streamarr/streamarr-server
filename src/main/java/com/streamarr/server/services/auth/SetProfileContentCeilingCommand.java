package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record SetProfileContentCeilingCommand(
    @NonNull UUID actingAccountId, @NonNull UUID profileId, int maximumAllowedRatingAge) {

  public SetProfileContentCeilingCommand {
    if (maximumAllowedRatingAge < 0) {
      throw new IllegalArgumentException("maximumAllowedRatingAge must not be negative");
    }
  }
}
