package com.streamarr.server.services.authorization.cedar;

/**
 * The shipped schema or policies are unusable; startup stops rather than authorize without them.
 */
class CedarBundleException extends RuntimeException {

  CedarBundleException(String message) {
    super(message);
  }

  CedarBundleException(String message, Throwable cause) {
    super(message, cause);
  }
}
