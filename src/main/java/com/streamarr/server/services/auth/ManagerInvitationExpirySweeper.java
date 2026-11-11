package com.streamarr.server.services.auth;

import com.streamarr.server.services.audit.SystemWork;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores EXPIRED on the invitations to manage a Profile whose time ran out, so that the change is
 * announced. Reads already treat such an invitation as EXPIRED; the sweep only makes the change
 * happen, for an invitation that nobody can answer as for any other.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagerInvitationExpirySweeper {

  private final ProfileManagerInvitationLifecycle invitationLifecycle;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  // Expiring an invitation updates its audit columns, so the write names the System actor.
  @Scheduled(
      initialDelayString = "${auth.managers.expiry-sweep-interval:1m}",
      fixedDelayString = "${auth.managers.expiry-sweep-interval:1m}")
  public void sweep() {
    SystemWork.runAsSystemActor(
        () -> transactionTemplate.executeWithoutResult(_ -> expireLapsedInvitations()));
  }

  private void expireLapsedInvitations() {
    var expired = invitationLifecycle.expireLapsed(clock.instant());
    if (expired > 0) {
      log.debug("Expired {} invitations to manage a Profile whose time ran out.", expired);
    }
  }
}
