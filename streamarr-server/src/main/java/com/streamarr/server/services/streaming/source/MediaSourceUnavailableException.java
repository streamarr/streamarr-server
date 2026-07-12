package com.streamarr.server.services.streaming.source;

public class MediaSourceUnavailableException extends RuntimeException {

  public MediaSourceUnavailableException() {
    super("Media source is unavailable");
  }

  public MediaSourceUnavailableException(Throwable cause) {
    super("Media source is unavailable", cause);
  }
}
