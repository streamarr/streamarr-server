package com.streamarr.server.exceptions;

public class ImageProcessingException extends RuntimeException {

  public ImageProcessingException(String message) {
    super(message);
  }

  public ImageProcessingException(Throwable cause) {
    super(cause);
  }
}
