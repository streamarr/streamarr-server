package com.streamarr.server.exceptions;

public class DevicePairingNotConfiguredException extends RuntimeException {

  public DevicePairingNotConfiguredException() {
    super("Device pairing requires a configured canonical base URL.");
  }
}
