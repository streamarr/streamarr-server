package com.streamarr.server.services.streaming.trust;

import java.util.Arrays;
import java.util.Objects;

public final class Sha256Digest {

  private static final int LENGTH = 32;

  private final byte[] value;

  public Sha256Digest(byte[] value) {
    Objects.requireNonNull(value);
    if (value.length != LENGTH) {
      throw new IllegalArgumentException("SHA-256 digest must contain exactly 32 bytes");
    }
    this.value = value.clone();
  }

  public byte[] bytes() {
    return value.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof Sha256Digest that && Arrays.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(value);
  }

  @Override
  public String toString() {
    return "Sha256Digest[redacted]";
  }
}
