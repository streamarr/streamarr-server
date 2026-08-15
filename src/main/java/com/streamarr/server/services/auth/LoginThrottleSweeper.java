package com.streamarr.server.services.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically evicts throttle entries whose window has passed; {@link LoginThrottle#sweepExpired}
 * documents why sprayed keys need a sweep.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginThrottleSweeper {

  private final LoginThrottle throttle;
  private final CredentialGuessThrottle credentialThrottle;

  /**
   * Removes expired entries from the login and credential throttles.
   */
  @Scheduled(fixedDelayString = "${auth.throttle.sweep-interval-ms:900000}")
  public void sweep() {
    var loginEntries = throttle.sweepExpired();
    var credentialEntries = credentialThrottle.sweepExpired();
    if (loginEntries + credentialEntries > 0) {
      log.debug(
          "Evicted {} stale login-throttle entries and {} stale credential-throttle entries.",
          loginEntries,
          credentialEntries);
    }
  }
}
