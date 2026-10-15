package com.streamarr.server.services.auth;

import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.services.audit.SystemWork;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Deletes expired pairing rows in every status. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceAuthorizationSweeper {

  private final DeviceAuthorizationRepository authorizationRepository;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  // Deleting an authorization clears it from the registrations it created, which updates their
  // audit columns, so the delete runs in a transaction that names the System actor.
  @Scheduled(fixedDelayString = "${auth.device.sweep-interval:15m}")
  public void sweep() {
    SystemWork.runAsSystemActor(
        () -> transactionTemplate.executeWithoutResult(_ -> deleteExpired()));
  }

  private void deleteExpired() {
    var deleted = authorizationRepository.deleteExpired(clock.instant());
    if (deleted > 0) {
      log.debug("Deleted {} expired device authorization rows.", deleted);
    }
  }
}
