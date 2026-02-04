package com.streamarr.server.exceptions;

public class InvalidSegmentPathException extends RuntimeException {

  public InvalidSegmentPathException(String segment) {
    super("Invalid segment path: " + segment);
  }
}
