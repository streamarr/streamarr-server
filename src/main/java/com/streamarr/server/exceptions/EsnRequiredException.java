package com.streamarr.server.exceptions;

/** A TV must present its ESN when requesting a pairing code (ADR 0024 §Devices). */
public class EsnRequiredException extends RuntimeException {

  public EsnRequiredException() {
    super("The device must present its ESN.");
  }
}
