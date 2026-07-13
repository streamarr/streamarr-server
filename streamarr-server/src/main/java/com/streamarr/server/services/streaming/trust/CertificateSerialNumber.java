package com.streamarr.server.services.streaming.trust;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

public record CertificateSerialNumber(BigInteger value) {

  private static final int MAXIMUM_DER_LENGTH = 20;

  public CertificateSerialNumber(BigInteger value) {
    Objects.requireNonNull(value);
    if (value.signum() <= 0) {
      throw new IllegalArgumentException("Certificate serial number must be positive");
    }
    if (value.toByteArray().length > MAXIMUM_DER_LENGTH) {
      throw new IllegalArgumentException(
          "Certificate serial number must fit a positive 20-byte DER integer");
    }
    this.value = value;
  }

  public static CertificateSerialNumber fromUnsignedBytes(byte[] value) {
    Objects.requireNonNull(value);
    return new CertificateSerialNumber(new BigInteger(1, value));
  }

  public byte[] unsignedBytes() {
    var encoded = value.toByteArray();
    if (encoded[0] != 0) {
      return encoded;
    }
    return Arrays.copyOfRange(encoded, 1, encoded.length);
  }
}
