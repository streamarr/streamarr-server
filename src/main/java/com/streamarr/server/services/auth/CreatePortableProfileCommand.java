package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record CreatePortableProfileCommand(
    @NonNull AuthenticatedIdentity authority,
    @NonNull String name,
    @NonNull ProfileKind kind,
    Integer maximumAllowedRatingAge,
    String pinHash) {

  public CreatePortableProfileCommand {
    if (maximumAllowedRatingAge != null && maximumAllowedRatingAge < 0) {
      throw new IllegalArgumentException("maximumAllowedRatingAge must not be negative");
    }
  }

  public UUID actingAccountId() {
    return authority.accountId();
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
        .formatted(actingAccountId(), name, kind, maximumAllowedRatingAge);
  }
}
