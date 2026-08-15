package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record HouseholdOwnershipTransferCommand(
    @NonNull UUID actingAccountId,
    @NonNull UUID householdId,
    @NonNull UUID targetAccountId,
    @NonNull String password,
    @NonNull String reason) {

  public static class HouseholdOwnershipTransferCommandBuilder {
    /**
     * Returns a redacted representation of the builder.
     *
     * @return a string that omits sensitive field values
     */
    @Override
    public String toString() {
      return "HouseholdOwnershipTransferCommandBuilder[REDACTED]";
    }
  }

  /**
   * Formats this command with its account identifiers, household identifier, and transfer reason.
   *
   * @return a string representation containing the identifiers and reason, without the password
   */
  @Override
  public String toString() {
    return "HouseholdOwnershipTransferCommand[actingAccountId=%s, householdId=%s, targetAccountId=%s, reason=%s]"
        .formatted(actingAccountId, householdId, targetAccountId, reason);
  }
}
