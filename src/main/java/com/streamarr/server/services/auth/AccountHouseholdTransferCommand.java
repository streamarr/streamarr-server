package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.HouseholdRole;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record AccountHouseholdTransferCommand(
    @NonNull UUID actingAccountId,
    @NonNull UUID targetAccountId,
    @NonNull UUID targetHouseholdId,
    @NonNull HouseholdRole targetRole,
    @NonNull String password,
    @NonNull String reason) {

  public static class AccountHouseholdTransferCommandBuilder {
    /**
     * Identifies the builder without exposing its field values.
     *
     * @return a redacted builder description
     */
    @Override
    public String toString() {
      return "AccountHouseholdTransferCommandBuilder[REDACTED]";
    }
  }

  /**
   * Returns a representation of this transfer command that excludes the password.
   *
   * @return a string containing the transfer details without the password
   */
  @Override
  public String toString() {
    return "AccountHouseholdTransferCommand[actingAccountId=%s, targetAccountId=%s, targetHouseholdId=%s, targetRole=%s, reason=%s]"
        .formatted(actingAccountId, targetAccountId, targetHouseholdId, targetRole, reason);
  }
}
