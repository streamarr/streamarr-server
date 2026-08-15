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
    /**
     * Provides a redacted representation of the builder.
     *
     * @return a redacted builder description
     */
    @Override
    public String toString() {
      return "CreatePortableProfileCommandBuilder[REDACTED]";
    }
  }

  /**
   * Formats the command as a string while redacting the PIN hash.
   *
   * @return a string containing the command's non-sensitive field values and a redacted PIN hash
   */
  @Override
  public String toString() {
    return "CreatePortableProfileCommand[actingAccountId=%s, name=%s, kind=%s, maximumAllowedRatingAge=%s, pinHash=<redacted>]"
        .formatted(actingAccountId, name, kind, maximumAllowedRatingAge);
  }
}
