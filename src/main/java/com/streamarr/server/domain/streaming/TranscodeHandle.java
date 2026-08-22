package com.streamarr.server.domain.streaming;

import java.util.OptionalLong;
import java.util.UUID;
import lombok.NonNull;

/**
 * One producer run for a variant. The {@code attemptId} identifies the run the handle was minted
 * for and survives status transitions, so a handle observed later can be matched against the
 * attempt that produced it. {@code processId} is present only for a producer running as a local OS
 * process; a remote dispatch has none.
 */
public record TranscodeHandle(
    @NonNull OptionalLong processId,
    @NonNull UUID attemptId,
    @NonNull TranscodeStatus status,
    int startSequenceNumber) {

  public TranscodeHandle(
      long processId, UUID attemptId, TranscodeStatus status, int startSequenceNumber) {
    this(OptionalLong.of(processId), attemptId, status, startSequenceNumber);
  }

  public static TranscodeHandle remoteDispatch(
      UUID attemptId, TranscodeStatus status, int startSequenceNumber) {
    return new TranscodeHandle(OptionalLong.empty(), attemptId, status, startSequenceNumber);
  }

  public TranscodeHandle withStatus(TranscodeStatus newStatus) {
    return new TranscodeHandle(processId, attemptId, newStatus, startSequenceNumber);
  }
}
