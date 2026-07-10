package com.streamarr.server.config.security;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;

/**
 * The current ES256 signing key pair plus every public key the decoder accepts — the current key
 * and any retired keys still verifying outstanding tokens during rotation.
 */
public record TokenSigningKeys(ECKey signingKey, JWKSet verificationKeys) {

  @Override
  public String toString() {
    return "TokenSigningKeys[REDACTED]";
  }
}
