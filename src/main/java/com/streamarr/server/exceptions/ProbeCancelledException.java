package com.streamarr.server.exceptions;

/** A probe attempt that stopped before it finished; it is retried without recording a failure. */
public class ProbeCancelledException extends TranscodeException {

  public ProbeCancelledException(String message) {
    super(message);
  }

  public ProbeCancelledException(String message, Throwable cause) {
    super(message, cause);
  }
}
