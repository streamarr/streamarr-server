package com.streamarr.server.services.notification;

import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention (ADR 0063 §Retention): deletes the history whose latest event is older than the
 * retention period, the news that its recipient read and the requests that resolved, a request that
 * reads as expired from its stored expiry included. Unread news and waiting requests stay.
 *
 * <p>It deletes a batch at a time, each batch in its own transaction, until a batch deletes
 * nothing. History never stops being history, so it takes no lock and reads no other table, and two
 * servers that sweep together delete each row once.
 */
@Slf4j
@Component
class NotificationRetentionSweeper {

  private final NotificationRepository notificationRepository;
  private final ServerSettingsService serverSettingsService;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;
  private final int batchSize;

  NotificationRetentionSweeper(
      NotificationRepository notificationRepository,
      ServerSettingsService serverSettingsService,
      TransactionTemplate transactionTemplate,
      Clock clock,
      @Value("${notifications.retention-sweep-batch-size:500}") int batchSize) {
    this.notificationRepository = notificationRepository;
    this.serverSettingsService = serverSettingsService;
    this.transactionTemplate = transactionTemplate;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  // A short initial delay: an instance restarted more often than the interval would never sweep.
  @Scheduled(
      initialDelayString = "${notifications.retention-sweep-initial-delay:5m}",
      fixedDelayString = "${notifications.retention-sweep-interval:24h}")
  public void sweep() {
    SystemWork.runAsSystemActor(this::deleteOldHistory);
  }

  private void deleteOldHistory() {
    var now = clock.instant();
    var cutoff = now.minus(serverSettingsService.notificationRetention());
    var deleted = 0;
    var batch = deleteBatch(cutoff, now);
    while (batch > 0) {
      deleted += batch;
      batch = deleteBatch(cutoff, now);
    }

    log.info("Deleted {} read or resolved Notifications older than {}.", deleted, cutoff);
  }

  private int deleteBatch(Instant cutoff, Instant now) {
    return Objects.requireNonNull(
        transactionTemplate.execute(
            _ -> notificationRepository.deleteHistoryBefore(cutoff, now, batchSize)));
  }
}
