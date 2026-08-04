package com.streamarr.server.controllers.auth.device;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Keeps every device-pairing response — success or failure — out of every cache. */
final class CredentialCacheHeaders {

  private CredentialCacheHeaders() {}

  static ResponseEntity.BodyBuilder nonCacheable(ResponseEntity.BodyBuilder builder) {
    return builder.cacheControl(CacheControl.noStore());
  }
}
