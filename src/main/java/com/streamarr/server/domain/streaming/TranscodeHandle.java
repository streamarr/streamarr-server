package com.streamarr.server.domain.streaming;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * One producer run for a variant. The {@code attemptId} identifies the run the handle was minted
 * for and survives status transitions, so a handle observed later can be matched against the
 * attempt that produced it. {@code processId} is present only for a producer running as a local OS
 * process; a remote dispatch has none.
 */
public record TranscodeHandle(
    OptionalLong processId, UUID attemptId, TranscodeStatus status, int startSequenceNumber) {

  public TranscodeHandle {
    Objects.requireNonNull(processId, "processId is required");
    Objects.requireNonNull(attemptId, "attemptId is required");
    Objects.requireNonNull(status, "status is required");
  }

  public TranscodeHandle(long processId, TranscodeStatus status) {
    this(processId, status, 0);
  }

  public TranscodeHandle(long processId, TranscodeStatus status, int startSequenceNumber) {
    this(OptionalLong.of(processId), UUID.randomUUID(), status, startSequenceNumber);
  }

  public TranscodeHandle(
      long processId, UUID attemptId, TranscodeStatus status, int startSequenceNumber) {
    this(OptionalLong.of(processId), attemptId, status, startSequenceNumber);
  }

  /** A producer dispatched to a remote worker: there is no local OS process to point at. */
  public static TranscodeHandle remoteDispatch(
      UUID attemptId, TranscodeStatus status, int startSequenceNumber) {
    return new TranscodeHandle(OptionalLong.empty(), attemptId, status, startSequenceNumber);
  }

  public TranscodeHandle withStatus(TranscodeStatus newStatus) {
    return new TranscodeHandle(processId, attemptId, newStatus, startSequenceNumber);
  }
}
