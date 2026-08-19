package com.streamarr.server.exceptions;

/**
 * No authorization decision could be made (engine failure, invalid slice or request, evaluation
 * diagnostic). The message is deliberately generic: Cedar diagnostics are logged and metered inside
 * the authorization module and never reach a client.
 */
public class AuthorizationUnavailableException extends RuntimeException {

  public AuthorizationUnavailableException() {
    super("Authorization is temporarily unavailable.");
  }
}
