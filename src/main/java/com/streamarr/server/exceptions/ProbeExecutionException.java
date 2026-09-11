package com.streamarr.server.exceptions;

public class ProbeExecutionException extends TranscodeException {

  public ProbeExecutionException() {
    super(GENERIC_MESSAGE);
  }

  public ProbeExecutionException(Throwable cause) {
    super(GENERIC_MESSAGE, cause);
  }
}
