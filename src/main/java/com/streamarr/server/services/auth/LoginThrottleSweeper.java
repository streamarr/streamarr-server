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

  @Scheduled(fixedDelayString = "${auth.throttle.sweep-interval-ms:900000}")
  public void sweep() {
    var evicted = throttle.sweepExpired();
    if (evicted > 0) {
      log.debug("Evicted {} stale login-throttle entries.", evicted);
    }
  }
}
