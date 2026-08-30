package com.streamarr.server.controllers.auth.device;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

/** The lookup view: never carries the device code. */
@Builder
public record DeviceAuthorizationResponse(
    String userCode,
    String deviceName,
    String status,
    Instant requestedAt,
    List<EligibleHousehold> households) {

  /** A Household the approver may bind the TV to. */
  public record EligibleHousehold(String id, String name) {}

  public static class DeviceAuthorizationResponseBuilder {

    @Override
    public String toString() {
      return "DeviceAuthorizationResponseBuilder[userCode=REDACTED, deviceName=%s]"
          .formatted(deviceName);
    }
  }

  @Override
  public String toString() {
    return "DeviceAuthorizationResponse[userCode=REDACTED, deviceName=%s, status=%s,"
            .formatted(deviceName, status)
        + " requestedAt=%s]".formatted(requestedAt);
  }
}
