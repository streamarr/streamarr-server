package com.streamarr.server.controllers.auth;

import com.streamarr.server.config.security.TokenSigningKeys;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public verification keys for external verifiers — the transcode tier scales horizontally and must
 * never hold a minting secret. Serving keys is not authorization: a consumer must also consult the
 * ADR 0017 joint authority or revocation silently stops applying to it (ADR 0016). Unknown-kid
 * refetch plus two-phase prepublication is the rotation mechanism (runbook in architecture.adoc);
 * the cache header paces polling and bounds how long a shared cache can hide a prepublished key.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

  private final TokenSigningKeys keys;

  @GetMapping("/.well-known/jwks.json")
  public ResponseEntity<Map<String, Object>> jwks() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
        .body(keys.verificationKeys().toPublicJWKSet().toJSONObject());
  }
}
