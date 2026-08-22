package com.streamarr.server.exceptions;

/**
 * One deliberate answer for every failed code presentation — malformed, unknown, expired, decided,
 * or digest mismatch. Distinguishing them would hand an enumerator an oracle.
 */
public class InvalidOneTimeCodeException extends RuntimeException {

  public InvalidOneTimeCodeException() {
    super("That code is not redeemable.");
  }
}
