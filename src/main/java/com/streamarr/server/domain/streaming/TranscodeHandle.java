package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.NonNull;

/**
 * One producer run for a variant. The {@code attemptId} identifies the run the handle was minted
 * for and survives status transitions, so a handle observed later can be matched against the
 * attempt that produced it. {@code processId} is present only for a producer running as a local OS
 * process; a remote dispatch has none. {@code encoder} is the worker-verified encoder that the
 * accepting worker advertises for the run's codec family; it is absent for a copy.
 */
public record TranscodeHandle(
    @NonNull OptionalLong processId,
    @NonNull UUID attemptId,
    @NonNull TranscodeStatus status,
    int startSequenceNumber,
    @NonNull Optional<WorkerVerifiedEncoder> encoder) {

  public TranscodeHandle(
      long processId, UUID attemptId, TranscodeStatus status, int startSequenceNumber) {
    this(OptionalLong.of(processId), attemptId, status, startSequenceNumber, Optional.empty());
  }

  public static TranscodeHandle remoteDispatch(
      UUID attemptId, TranscodeStatus status, int startSequenceNumber) {
    return new TranscodeHandle(
        OptionalLong.empty(), attemptId, status, startSequenceNumber, Optional.empty());
  }

  public TranscodeHandle withStatus(TranscodeStatus newStatus) {
    return new TranscodeHandle(processId, attemptId, newStatus, startSequenceNumber, encoder);
  }

  public TranscodeHandle withEncoder(@NonNull WorkerVerifiedEncoder dispatchedEncoder) {
    return new TranscodeHandle(
        processId, attemptId, status, startSequenceNumber, Optional.of(dispatchedEncoder));
  }
}
