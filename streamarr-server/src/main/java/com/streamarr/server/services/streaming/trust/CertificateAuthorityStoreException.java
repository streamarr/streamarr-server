package com.streamarr.server.services.streaming.trust;

public final class CertificateAuthorityStoreException extends RuntimeException {

  public CertificateAuthorityStoreException(String message) {
    super(message);
  }

  public CertificateAuthorityStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
