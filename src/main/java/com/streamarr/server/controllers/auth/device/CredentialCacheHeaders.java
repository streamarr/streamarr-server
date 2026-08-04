package com.streamarr.server.controllers.auth.device;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * Keeps every device-pairing response — success or failure — out of every cache. RFC 6749 §5.1,
 * which RFC 8628 §3.5 defers to for the token response, requires both headers; Spring's
 * CacheControl emits only the first, so Pragma is set alongside it for HTTP/1.0 intermediaries.
 * Issuance hands out a device code and the poll hands out tokens, so both surfaces are in scope.
 */
final class CredentialCacheHeaders {

  private CredentialCacheHeaders() {}

  static ResponseEntity.BodyBuilder nonCacheable(ResponseEntity.BodyBuilder builder) {
    return builder.cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache");
  }
}
