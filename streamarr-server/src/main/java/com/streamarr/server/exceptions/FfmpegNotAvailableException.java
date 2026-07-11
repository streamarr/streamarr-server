package com.streamarr.server.exceptions;

public class FfmpegNotAvailableException extends RuntimeException {

  public static final String GENERIC_MESSAGE =
      "Media processing is unavailable. Check server logs for details";

  public FfmpegNotAvailableException(String message) {
    super(message);
  }

  public FfmpegNotAvailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
