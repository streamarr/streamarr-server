package com.streamarr.server.exceptions;

import java.util.UUID;

public class TranscodeLimitReachedException extends RuntimeException {

  public TranscodeLimitReachedException(UUID sessionId) {
    super("The transcode limit leaves no place for the first job attempt of session " + sessionId);
  }
}
