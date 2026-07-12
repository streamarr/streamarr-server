package com.streamarr.transcode.engine.segment;

public class InvalidSegmentPathException extends RuntimeException {

  public InvalidSegmentPathException(String segment) {
    super("Invalid segment path: " + segment);
  }
}
