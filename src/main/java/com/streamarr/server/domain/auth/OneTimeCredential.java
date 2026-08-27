package com.streamarr.server.domain.auth;

import java.time.Instant;

/**
 * A single-use opaque credential (ADR 0024): resolved by its stable publicId, proven by the SHA-256
 * digest of its secret, and acted on only while PENDING and unexpired.
 */
public interface OneTimeCredential {

  String getPublicId();

  byte[] getSecretDigest();

  /** PENDING and unexpired at {@code now}: the only state a presented code may act on. */
  boolean isRedeemableAt(Instant now);
}
