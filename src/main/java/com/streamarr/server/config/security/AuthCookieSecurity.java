package com.streamarr.server.config.security;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Whether auth cookies carry the {@code Secure} attribute. Nothing else about the cookies is
 * negotiable: {@code httpOnly} and {@code SameSite=Strict} hold in every mode, because ADR 0016
 * rests on browsers never touching tokens from script.
 */
@Getter
@RequiredArgsConstructor
public enum AuthCookieSecurity {

  /** Cookies travel only over HTTPS. The only value production may hold. */
  SECURE(true),

  /**
   * Cookies omit {@code Secure} so a cookie-mode session survives {@code http://localhost} in
   * Safari, which — unlike Chrome and Firefox — refuses to store or send a Secure cookie over
   * cleartext even on localhost.
   */
  INSECURE_DEVELOPMENT(false);

  private final boolean secure;
}
