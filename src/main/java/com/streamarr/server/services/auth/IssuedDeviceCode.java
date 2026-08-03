package com.streamarr.server.services.auth;

import lombok.Builder;

/** Issuance's answer to the device: what to poll with, what to display, and at what cadence. */
@Builder
public record IssuedDeviceCode(
    String deviceCode, String userCode, String verificationUri, int interval, long expiresIn) {

  public static class IssuedDeviceCodeBuilder {

    @Override
    public String toString() {
      return "IssuedDeviceCodeBuilder[deviceCode=REDACTED, userCode=REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "IssuedDeviceCode[deviceCode=REDACTED, userCode=REDACTED, verificationUri=%s,"
            .formatted(verificationUri)
        + " interval=%d, expiresIn=%d]".formatted(interval, expiresIn);
  }
}
