package com.streamarr.server.exceptions;

/** A device-bound session may watch but never administer or step up (ADR 0024 §Devices). */
public class DeviceBoundSessionException extends RuntimeException {

  public DeviceBoundSessionException() {
    super("A device-bound session cannot do that.");
  }
}
