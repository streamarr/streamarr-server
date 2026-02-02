package com.streamarr.server.graphql.cursor;

public class InvalidCursorException extends RuntimeException {

  public InvalidCursorException(String message) {
    super(message);
  }
}
