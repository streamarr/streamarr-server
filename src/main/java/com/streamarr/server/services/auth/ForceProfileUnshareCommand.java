package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ForceProfileUnshareCommand(
    @NonNull UUID actingAccountId,
    @NonNull UUID shareId,
    @NonNull String password,
    @NonNull String reason) {

  public static class ForceProfileUnshareCommandBuilder {
    @Override
    public String toString() {
      return "ForceProfileUnshareCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "ForceProfileUnshareCommand[actingAccountId=%s, shareId=%s, reason=%s]"
        .formatted(actingAccountId, shareId, reason);
  }
}
