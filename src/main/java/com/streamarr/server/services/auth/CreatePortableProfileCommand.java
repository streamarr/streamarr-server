package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileClassification;
import java.util.UUID;
import lombok.Builder;

@Builder
public record CreatePortableProfileCommand(
    UUID actingAccountId,
    String name,
    ProfileClassification classification,
    Integer maximumAllowedRatingAge,
    String pinHash) {

  @Override
  public String toString() {
    return "CreatePortableProfileCommand[actingAccountId=%s, name=%s, classification=%s, maximumAllowedRatingAge=%s, pinHash=<redacted>]"
        .formatted(actingAccountId, name, classification, maximumAllowedRatingAge);
  }
}
