package com.streamarr.server.controllers.auth.device;

public record DeviceDecisionRequest(String userCode, String decision) {

  @Override
  public String toString() {
    return "DeviceDecisionRequest[userCode=REDACTED, decision=%s]".formatted(decision);
  }
}
