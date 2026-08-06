package com.streamarr.server.exceptions;

public class DeviceCodeNotPendingException extends RuntimeException {

  public DeviceCodeNotPendingException() {
    super("That pairing request has already been decided.");
  }
}
