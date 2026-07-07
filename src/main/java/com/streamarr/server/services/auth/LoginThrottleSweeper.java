package com.streamarr.server.services.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically evicts throttle entries whose window has passed. A spray of unique emails or sources
 * would otherwise grow the throttle map without bound — sprayed keys are never touched again, so
 * only a sweep can reclaim them.
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
