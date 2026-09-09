package com.streamarr.server.controllers.auth.device;

import lombok.Builder;

@Builder
public record DeviceCodeResponse(
    String deviceCode, String userCode, String verificationUri, int interval, long expiresIn) {

  public static class DeviceCodeResponseBuilder {

    @Override
    public String toString() {
      return "DeviceCodeResponseBuilder[deviceCode=REDACTED, userCode=REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "DeviceCodeResponse[deviceCode=REDACTED, userCode=REDACTED, verificationUri=%s,"
            .formatted(verificationUri)
        + " interval=%d, expiresIn=%d]".formatted(interval, expiresIn);
  }
}
