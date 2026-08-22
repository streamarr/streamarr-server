package com.streamarr.server.services.authorization.cedar;

/** The Cedar engine could not load, validate, or evaluate the self-check on this platform. */
public class CedarSelfCheckException extends RuntimeException {

  public CedarSelfCheckException(String message) {
    super(message);
  }

  public CedarSelfCheckException(String message, Throwable cause) {
    super(message, cause);
  }
}
