package com.streamarr.server.exceptions;

public class DeviceCodeExpiredException extends RuntimeException {

  public DeviceCodeExpiredException() {
    super("That pairing code has expired; start a new one on the device.");
  }
}
