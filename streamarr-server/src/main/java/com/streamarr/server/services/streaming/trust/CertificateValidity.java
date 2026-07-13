package com.streamarr.server.services.streaming.trust;

import java.time.Instant;
import java.util.Objects;

public record CertificateValidity(Instant notBefore, Instant notAfter) {

  public CertificateValidity {
    Objects.requireNonNull(notBefore);
    Objects.requireNonNull(notAfter);
    if (notBefore.getNano() != 0 || notAfter.getNano() != 0) {
      throw new IllegalArgumentException("Certificate validity must use whole-second precision");
    }
    if (!notAfter.isAfter(notBefore)) {
      throw new IllegalArgumentException("Certificate expiry must follow its validity start");
    }
  }
}
