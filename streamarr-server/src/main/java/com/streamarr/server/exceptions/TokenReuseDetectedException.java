package com.streamarr.server.exceptions;

public class TokenReuseDetectedException extends RuntimeException {

  public TokenReuseDetectedException() {
    super("Refresh token reuse detected; the session has been revoked.");
  }
}
