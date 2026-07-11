package com.streamarr.server.exceptions;

public class TranscodeException extends RuntimeException {

  public static final String GENERIC_MESSAGE =
      "Media processing failed. Check server logs for details";

  public TranscodeException(String message) {
    super(message);
  }

  public TranscodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
