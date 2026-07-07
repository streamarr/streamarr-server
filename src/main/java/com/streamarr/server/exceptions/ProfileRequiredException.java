package com.streamarr.server.exceptions;

public class ProfileRequiredException extends RuntimeException {

  public ProfileRequiredException() {
    super("A profile must be selected for this operation.");
  }
}
