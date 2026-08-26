package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import java.time.Instant;
import lombok.Builder;

/**
 * What an approver is shown before committing. Deliberately omits the device code: the approval
 * surface never needs it, and a leaked one would let the page's viewer redeem the pairing itself.
 */
@Builder
public record DeviceAuthorizationDetails(
    String userCode, String deviceName, DeviceAuthorizationStatus status, Instant requestedAt) {

  public static class DeviceAuthorizationDetailsBuilder {

    @Override
    public String toString() {
      return "DeviceAuthorizationDetailsBuilder[userCode=REDACTED, deviceName=%s]"
          .formatted(deviceName);
    }
  }

  @Override
  public String toString() {
    return "DeviceAuthorizationDetails[userCode=REDACTED, deviceName=%s, status=%s]"
        .formatted(deviceName, status);
  }
}
