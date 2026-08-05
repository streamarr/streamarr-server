package com.streamarr.server.config.security;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The deployment-wide cookie transport policy and corresponding CSRF cookie name. */
@Getter
@RequiredArgsConstructor
public enum AuthCookiePolicy {

  /** Cookies travel only over HTTPS and the CSRF nonce is host-bound. */
  SECURE(true, AuthCookies.CSRF_COOKIE),

  /**
   * Cookies omit {@code Secure} and the CSRF nonce uses an unprefixed name so a cookie-mode session
   * survives {@code http://localhost} in Safari, which — unlike Chrome and Firefox — refuses to
   * store or send a Secure cookie over cleartext even on localhost.
   */
  INSECURE_DEVELOPMENT(false, AuthCookies.INSECURE_DEVELOPMENT_CSRF_COOKIE);

  private final boolean secure;
  private final String csrfCookieName;
}
