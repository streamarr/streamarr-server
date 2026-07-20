package com.streamarr.server.domain.streaming;

import java.util.Objects;

public record TranscodeHandle(long processId, TranscodeStatus status, int startSequenceNumber) {

  public TranscodeHandle {
    Objects.requireNonNull(status, "status is required");
  }

  public TranscodeHandle(long processId, TranscodeStatus status) {
    this(processId, status, 0);
  }
}
