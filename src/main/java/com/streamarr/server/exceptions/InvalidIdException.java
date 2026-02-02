package com.streamarr.server.exceptions;

public class InvalidIdException extends RuntimeException {

  public InvalidIdException(String id) {
    super("Invalid ID format: " + id);
  }
}
