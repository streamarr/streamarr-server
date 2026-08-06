package com.streamarr.server.controllers.auth.device;

public record DeviceTokenRequest(String deviceCode) {

  @Override
  public String toString() {
    return "DeviceTokenRequest[deviceCode=REDACTED]";
  }
}
