package com.streamarr.server.exceptions;

/**
 * A completion for a reservation that is unknown or already completed: a programming error in the
 * calling service, not a journal outage. It extends RuntimeException directly so that Spring's
 * repository exception translation (which maps IllegalStateException to a DataAccessException)
 * and the gate's fail-closed mapping both leave it alone.
 */
public class CredentialAttemptNotPendingException extends RuntimeException {

  public CredentialAttemptNotPendingException() {
    super("Credential attempt reservation is not pending");
  }
}
