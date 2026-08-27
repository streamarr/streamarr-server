package com.streamarr.server.exceptions;

/**
 * One deliberate answer for every failed code presentation below the guess budget — malformed,
 * unknown, expired, decided, or digest mismatch — so the client learns nothing about the row.
 * Beyond the budget the per-publicId throttle answers instead, which is acceptable because public
 * ids are 72 random bits and not enumerable.
 */
public class InvalidOneTimeCodeException extends RuntimeException {

  public InvalidOneTimeCodeException() {
    super("That code is not redeemable.");
  }
}
