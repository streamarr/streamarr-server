package com.streamarr.server.exceptions;

/** The supplied ESN cannot be stored as a Device registration identity. */
public class InvalidEsnException extends RuntimeException {

  public InvalidEsnException(int maximumLength) {
    super("The device ESN must contain at most %d characters.".formatted(maximumLength));
  }
}
