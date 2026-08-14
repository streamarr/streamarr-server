package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.HouseholdRole;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record AccountHouseholdTransferCommand(
    UUID actingAccountId,
    UUID targetAccountId,
    UUID targetHouseholdId,
    HouseholdRole targetRole,
    String password,
    String reason) {

  public AccountHouseholdTransferCommand {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(targetAccountId, "targetAccountId");
    Objects.requireNonNull(targetHouseholdId, "targetHouseholdId");
    Objects.requireNonNull(targetRole, "targetRole");
    Objects.requireNonNull(password, "password");
    Objects.requireNonNull(reason, "reason");
  }

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
