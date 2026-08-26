package com.streamarr.server.exceptions;

/**
 * A credential that was checked and refused. {@code CredentialAttemptGate} journals a thrown
 * subclass as a FAILED attempt; any other failure leaves the reservation pending.
 */
public abstract class CredentialVerificationException extends RuntimeException {

  protected CredentialVerificationException(String message) {
    super(message);
  }
}
