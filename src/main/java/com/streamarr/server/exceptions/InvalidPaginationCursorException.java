package com.streamarr.server.exceptions;

public class InvalidPaginationCursorException extends RuntimeException {

  public InvalidPaginationCursorException(String message) {
    super(message);
  }
}
