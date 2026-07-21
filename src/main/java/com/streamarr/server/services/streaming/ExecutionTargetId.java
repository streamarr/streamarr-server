package com.streamarr.server.services.streaming;

import java.util.Objects;

/**
 * Identity of one place a producer can run. Locally there is exactly one; remotely each live worker
 * connection (its {@code worker_session_id}) is a distinct target, so a reconnecting worker is a
 * new target by construction (ADR 0018: the live connection is the fence).
 */
public record ExecutionTargetId(String value) {

  public ExecutionTargetId {
    Objects.requireNonNull(value, "value is required");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }

  public static final ExecutionTargetId LOCAL = new ExecutionTargetId("local");
}
