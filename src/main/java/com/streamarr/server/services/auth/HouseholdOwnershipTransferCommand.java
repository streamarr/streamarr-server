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
    @Override
    public String toString() {
      return "HouseholdOwnershipTransferCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "HouseholdOwnershipTransferCommand[actingAccountId=%s, householdId=%s, targetAccountId=%s, reason=%s]"
        .formatted(actingAccountId, householdId, targetAccountId, reason);
  }
}
