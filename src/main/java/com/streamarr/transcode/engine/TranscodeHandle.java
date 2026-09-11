package com.streamarr.transcode.engine;

import java.util.OptionalLong;
import java.util.UUID;
import lombok.NonNull;

public record TranscodeHandle(
    @NonNull OptionalLong processId,
    @NonNull UUID attemptId,
    @NonNull TranscodeStatus status,
    int startSequenceNumber) {

  public TranscodeHandle(
      long processId, UUID attemptId, TranscodeStatus status, int startSequenceNumber) {
    this(OptionalLong.of(processId), attemptId, status, startSequenceNumber);
  }
}
