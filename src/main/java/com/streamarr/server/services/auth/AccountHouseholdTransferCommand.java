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
    @Override
    public String toString() {
      return "AccountHouseholdTransferCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "AccountHouseholdTransferCommand[actingAccountId=%s, targetAccountId=%s, targetHouseholdId=%s, targetRole=%s, reason=%s]"
        .formatted(actingAccountId, targetAccountId, targetHouseholdId, targetRole, reason);
  }
}
