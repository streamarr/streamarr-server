package com.streamarr.server.services.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically evicts throttle entries whose window has passed; {@link LoginThrottle#sweepExpired}
 * documents why sprayed keys need a sweep. Account and Profile credential budgets cannot be sprayed
 * but would linger for deleted Accounts; the opaque-code budget is keyed by presented publicId and
 * capped, so the sweep also returns its key slots.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginThrottleSweeper {

  private final LoginThrottle throttle;
  private final CredentialGuessThrottle credentialThrottle;

  @Scheduled(fixedDelayString = "${auth.throttle.sweep-interval-ms:900000}")
  public void sweep() {
    var evicted = throttle.sweepExpired() + credentialThrottle.sweepExpired();
    if (evicted > 0) {
      log.debug("Evicted {} stale throttle entries.", evicted);
    }
  }
}
