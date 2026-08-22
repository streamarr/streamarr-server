package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthTokenProperties;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether an identity's reauthentication ceremony is still current (ADR 0024 §Fresh
 * reauthentication): the claim exists, is not future-dated, and is inside the configured window.
 * Missing or stale claims are simply not fresh — never an error.
 */
@Component
@RequiredArgsConstructor
public class ReauthenticationFreshness {

  private final AuthTokenProperties properties;
  private final Clock clock;

  public boolean isFresh(AuthenticatedIdentity identity) {
    var reauthenticatedAt = identity.reauthenticatedAt();
    if (reauthenticatedAt.isEmpty()) {
      return false;
    }

    var now = clock.instant();
    var instant = reauthenticatedAt.orElseThrow();
    if (instant.isAfter(now)) {
      return false;
    }

    return now.isBefore(instant.plus(properties.reauthenticationWindow()));
  }
}
