package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;

/**
 * The approver's two choices. Denial is a first-class outcome, not the absence of approval: without
 * it a client could never distinguish "refused" from "still waiting".
 */
public enum DeviceDecision {
  APPROVE(DeviceAuthorizationStatus.APPROVED),
  DENY(DeviceAuthorizationStatus.DENIED);

  private final DeviceAuthorizationStatus resultingStatus;

  DeviceDecision(DeviceAuthorizationStatus resultingStatus) {
    this.resultingStatus = resultingStatus;
  }

  public DeviceAuthorizationStatus resultingStatus() {
    return resultingStatus;
  }
}
