package com.streamarr.server.repositories.auth;

import java.time.Instant;
import lombok.Builder;

/** One issuance attempt: the row to insert, and the cap it must fit under. */
@Builder
public record DeviceAuthorizationInsertCommand(
    String deviceCodeDigest,
    String userCode,
    String deviceName,
    Instant expiresAt,
    Instant nextPollAt,
    int pollIntervalSeconds,
    int maxOutstanding,
    Instant now) {

  @Override
  public String toString() {
    return "DeviceAuthorizationInsertCommand[deviceCodeDigest=REDACTED, userCode=REDACTED,"
        + " deviceName=%s, expiresAt=%s, maxOutstanding=%d]"
            .formatted(deviceName, expiresAt, maxOutstanding);
  }

  public static class DeviceAuthorizationInsertCommandBuilder {

    @Override
    public String toString() {
      return "DeviceAuthorizationInsertCommandBuilder[deviceCodeDigest=REDACTED,"
          + " userCode=REDACTED]";
    }
  }
}
