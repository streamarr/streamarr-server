package com.streamarr.transcode.engine.job;

import com.streamarr.transcode.engine.error.TranscodeException;

public final class TranscodeEngineException extends TranscodeException {

  public enum Reason {
    STALE_GENERATION,
    JOB_CONFLICT,
    SESSION_CONFLICT,
    INVALID_SPECIFICATION,
    STARTUP_FAILED,
    CLEANUP_PENDING,
    SHUTTING_DOWN
  }

  private final Reason reason;

  public TranscodeEngineException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public TranscodeEngineException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
