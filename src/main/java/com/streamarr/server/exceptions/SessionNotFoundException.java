package com.streamarr.server.exceptions;

import java.util.UUID;

public class SessionNotFoundException extends RuntimeException {

  public SessionNotFoundException(UUID sessionId) {
    super("Streaming session not found: " + sessionId);
  }
}
