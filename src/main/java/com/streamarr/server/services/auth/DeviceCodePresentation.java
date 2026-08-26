package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A user code typed by an authenticated approver. The approver's Account keys the guessing budget
 * (ADR 0021): lookup is the enumeration oracle, and an unknown code has no row of its own.
 */
@Builder
public record DeviceCodePresentation(
    String userCode, @NonNull UUID approverAccountId, @NonNull String ipAddress) {

  @Override
  public String toString() {
    return "DeviceCodePresentation[userCode=REDACTED, approverAccountId=%s, ipAddress=%s]"
        .formatted(approverAccountId, ipAddress);
  }

  public static class DeviceCodePresentationBuilder {

    @Override
    public String toString() {
      return "DeviceCodePresentationBuilder[REDACTED]";
    }
  }
}
