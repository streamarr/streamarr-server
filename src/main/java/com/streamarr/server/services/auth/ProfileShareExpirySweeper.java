package com.streamarr.server.services.auth;

import com.streamarr.server.services.audit.SystemWork;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores EXPIRED on the offers whose time ran out, so the change reaches those following share
 * changes. Reads already treat such an offer as EXPIRED; the sweep only makes the change happen.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileShareExpirySweeper {

  private final ProfileShareLifecycle shareLifecycle;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  // Expiring an offer updates its audit columns, so the write names the System actor.
  @Scheduled(
      initialDelayString = "${auth.sharing.expiry-sweep-interval:1m}",
      fixedDelayString = "${auth.sharing.expiry-sweep-interval:1m}")
  public void sweep() {
    SystemWork.runAsSystemActor(
        () -> transactionTemplate.executeWithoutResult(_ -> expireLapsedOffers()));
  }

  private void expireLapsedOffers() {
    var expired = shareLifecycle.expireLapsedOffers(clock.instant());
    if (expired > 0) {
      log.debug("Expired {} Profile share offers whose time ran out.", expired);
    }
  }
}
