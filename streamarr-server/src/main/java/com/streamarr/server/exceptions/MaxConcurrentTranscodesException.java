package com.streamarr.server.exceptions;

public class MaxConcurrentTranscodesException extends RuntimeException {

  public MaxConcurrentTranscodesException(int maxConcurrent) {
    super("Maximum concurrent transcodes reached: " + maxConcurrent);
  }
}
