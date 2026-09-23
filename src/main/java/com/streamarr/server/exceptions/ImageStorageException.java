package com.streamarr.server.exceptions;

public class ImageStorageException extends RuntimeException {

  public ImageStorageException(Throwable cause) {
    super("Failed to store image variants", cause);
  }
}
