package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record HouseholdOwnershipTransferCommand(
    UUID actingAccountId, UUID householdId, UUID targetAccountId, String password, String reason) {

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
