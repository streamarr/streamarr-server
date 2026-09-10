package com.streamarr.server.exceptions;

public class DeviceCodeNotFoundException extends CredentialVerificationException {

  public DeviceCodeNotFoundException() {
    super("No pairing request matches that code.");
  }
}
