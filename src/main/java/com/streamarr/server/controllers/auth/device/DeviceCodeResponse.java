package com.streamarr.server.controllers.auth.device;

import lombok.Builder;

/**
 * verificationUriComplete is deliberately absent in v1: the field name is reserved for the deferred
 * QR accelerator, and shipping protocol surface no client consumes is how speculative layers get
 * built.
 */
@Builder
public record DeviceCodeResponse(
    String deviceCode, String userCode, String verificationUri, int interval, long expiresIn) {

  @Override
  public String toString() {
    return "DeviceCodeResponse[deviceCode=REDACTED, userCode=REDACTED, verificationUri=%s,"
            .formatted(verificationUri)
        + " interval=%d, expiresIn=%d]".formatted(interval, expiresIn);
  }
}
