package com.streamarr.server.services.streaming.trust;

public final class InstallationTrustException extends RuntimeException {

  public InstallationTrustException(String message) {
    super(message);
  }

  public InstallationTrustException(String message, Throwable cause) {
    super(message, cause);
  }
}
