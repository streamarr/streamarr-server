package com.streamarr.server.controllers.auth.device;

import java.util.UUID;

public record DeviceDecisionRequest(String userCode, String decision, UUID householdId) {

  @Override
  public String toString() {
    return "DeviceDecisionRequest[userCode=REDACTED, decision=%s, householdId=%s]"
        .formatted(decision, householdId);
  }
}
