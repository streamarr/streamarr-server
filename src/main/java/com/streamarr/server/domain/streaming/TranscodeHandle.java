package com.streamarr.server.domain.streaming;

import java.util.Objects;
import java.util.UUID;

/**
 * One producer run for a variant. The {@code attemptId} identifies the run the handle was minted
 * for and survives status transitions, so a handle observed later can be matched against the
 * attempt that produced it.
 */
public record TranscodeHandle(
    long processId, UUID attemptId, TranscodeStatus status, int startSequenceNumber) {

  public TranscodeHandle {
    Objects.requireNonNull(attemptId, "attemptId is required");
    Objects.requireNonNull(status, "status is required");
  }

  public TranscodeHandle(long processId, TranscodeStatus status) {
    this(processId, status, 0);
  }

  public TranscodeHandle(long processId, TranscodeStatus status, int startSequenceNumber) {
    this(processId, UUID.randomUUID(), status, startSequenceNumber);
  }

  public TranscodeHandle withStatus(TranscodeStatus newStatus) {
    return new TranscodeHandle(processId, attemptId, newStatus, startSequenceNumber);
  }
}
