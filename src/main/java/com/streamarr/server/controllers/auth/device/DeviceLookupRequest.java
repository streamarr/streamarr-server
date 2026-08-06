package com.streamarr.server.controllers.auth.device;

public record DeviceLookupRequest(String userCode) {

  @Override
  public String toString() {
    return "DeviceLookupRequest[userCode=REDACTED]";
  }
}
