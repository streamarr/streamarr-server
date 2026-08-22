package com.streamarr.server.exceptions;

/** The presented ESN is refused in the chosen Household or server-wide (ADR 0024 §Devices). */
public class EsnBlockedException extends RuntimeException {

  public EsnBlockedException() {
    super("That device is blocked.");
  }
}
