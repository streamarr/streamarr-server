package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileKind;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record CreatePortableProfileCommand(
    UUID actingAccountId,
    String name,
    ProfileKind kind,
    Integer maximumAllowedRatingAge,
    String pinHash) {

  public CreatePortableProfileCommand {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
  }

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
