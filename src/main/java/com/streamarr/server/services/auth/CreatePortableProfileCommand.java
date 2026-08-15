package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record CreatePortableProfileCommand(
    @NonNull UUID actingAccountId,
    @NonNull String name,
    @NonNull ProfileKind kind,
    Integer maximumAllowedRatingAge,
    String pinHash) {

  public static class CreatePortableProfileCommandBuilder {
    @Override
    public String toString() {
      return "CreatePortableProfileCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "CreatePortableProfileCommand[actingAccountId=%s, name=%s, kind=%s, maximumAllowedRatingAge=%s, pinHash=<redacted>]"
        .formatted(actingAccountId, name, kind, maximumAllowedRatingAge);
  }
}
