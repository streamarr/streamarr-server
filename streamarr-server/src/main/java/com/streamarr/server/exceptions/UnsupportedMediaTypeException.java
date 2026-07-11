package com.streamarr.server.exceptions;

public class UnsupportedMediaTypeException extends RuntimeException {

  public UnsupportedMediaTypeException(String mediaType) {
    super("Unsupported media type: " + mediaType);
  }
}
