package com.streamarr.server.exceptions;

public class DeviceCodeNotFoundException extends RuntimeException {

  public DeviceCodeNotFoundException() {
    super("No pairing request matches that code.");
  }
}
