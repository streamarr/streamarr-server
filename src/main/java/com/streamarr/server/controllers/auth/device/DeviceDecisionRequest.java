package com.streamarr.server.controllers.auth.device;

public record DeviceDecisionRequest(String userCode, String decision, String householdId) {

  @Override
  public String toString() {
    return "DeviceDecisionRequest[userCode=REDACTED, decision=%s, householdId=%s]"
        .formatted(decision, householdId);
  }
}
