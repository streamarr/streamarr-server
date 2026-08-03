package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record DeviceDecisionCommand(
    String userCode, DeviceDecision decision, UUID decidedByAccountId) {

  public static class DeviceDecisionCommandBuilder {

    @Override
    public String toString() {
      return "DeviceDecisionCommandBuilder[userCode=REDACTED, decision=%s]".formatted(decision);
    }
  }

  @Override
  public String toString() {
    return "DeviceDecisionCommand[userCode=REDACTED, decision=%s, decidedByAccountId=%s]"
        .formatted(decision, decidedByAccountId);
  }
}
