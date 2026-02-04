package com.streamarr.server.exceptions;

public class FfmpegNotAvailableException extends RuntimeException {

  public FfmpegNotAvailableException(String message) {
    super(message);
  }

  public FfmpegNotAvailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
