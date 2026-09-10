package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A user code submitted by an authenticated approver. The approver's Account keys the guessing
 * budget (ADR 0021): lookup is the enumeration oracle, and an unknown code has no row of its own.
 */
@Builder
public record DeviceCodeSubmission(
    String userCode, @NonNull UUID approverAccountId, @NonNull String ipAddress) {

  @Override
  public String toString() {
    return "DeviceCodeSubmission[userCode=REDACTED, approverAccountId=%s, ipAddress=%s]"
        .formatted(approverAccountId, ipAddress);
  }

  public static class DeviceCodeSubmissionBuilder {

    @Override
    public String toString() {
      return "DeviceCodeSubmissionBuilder[REDACTED]";
    }
  }
}
