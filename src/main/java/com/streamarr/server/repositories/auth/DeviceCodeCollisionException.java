package com.streamarr.server.repositories.auth;

public class DeviceCodeCollisionException extends RuntimeException {

  public DeviceCodeCollisionException(Throwable cause) {
    super("A device authorization already uses that device code.", cause);
  }
}
