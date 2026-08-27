package com.streamarr.server.exceptions;

public class InvalidEmailAddressException extends RuntimeException {

  public InvalidEmailAddressException() {
    super("Not the shape of an email address.");
  }
}
