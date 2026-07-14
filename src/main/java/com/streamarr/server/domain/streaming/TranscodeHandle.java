package com.streamarr.server.domain.streaming;

public record TranscodeHandle(long processId, TranscodeStatus status, int startSequenceNumber) {

  public TranscodeHandle(long processId, TranscodeStatus status) {
    this(processId, status, 0);
  }
}
