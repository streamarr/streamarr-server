package com.streamarr.server.exceptions;

public class InvalidPaginationArgumentException extends RuntimeException {

  public InvalidPaginationArgumentException(String message) {
    super(message);
  }
}
