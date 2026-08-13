package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ForceProfileUnshareCommand(
    UUID actingAccountId, UUID shareId, String password, String reason) {

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
