package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.repositories.notification.ShareNotificationRepository;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention (ADR 0063 §Retention): deletes the history whose latest event is older than the
 * retention period, the news that its recipient read and the requests that resolved, and the unread
 * news that the guard hides from its recipient after the same period. Unread news that the guard
 * shows, a disabled Account's unread news, waiting requests, and news whose guard decision fails
 * stay.
 *
 * <p>The unread news is read, decided and deleted a batch at a time, each batch's deletion in its
 * own transaction, so one sweep holds a bounded number of Notifications however many have aged.
 */
@Slf4j
@Component
class NotificationRetentionSweeper {

  private final ShareNotificationRepository notificationRepository;
  private final NotificationReads notificationReads;
  private final ServerSettingsService serverSettingsService;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;
  private final int batchSize;

  NotificationRetentionSweeper(
      ShareNotificationRepository notificationRepository,
      NotificationReads notificationReads,
      ServerSettingsService serverSettingsService,
      TransactionTemplate transactionTemplate,
      Clock clock,
      @Value("${notifications.retention-sweep-batch-size:500}") int batchSize) {
    this.notificationRepository = notificationRepository;
    this.notificationReads = notificationReads;
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
    SystemWork.runAsSystemActor(this::deleteOldNotifications);
  }

  private void deleteOldNotifications() {
    var now = clock.instant();
    var cutoff = now.minus(serverSettingsService.notificationRetention());
    transactionTemplate.executeWithoutResult(_ -> deleteHistory(cutoff, now));
    var batch = notificationRepository.findUnreadNewsBefore(cutoff, Optional.empty(), batchSize);
    while (!batch.isEmpty()) {
      deleteHidden(batch, cutoff);
      Optional<UUID> after = Optional.of(batch.getLast().getId());
      batch = notificationRepository.findUnreadNewsBefore(cutoff, after, batchSize);
    }
  }

  private void deleteHistory(Instant cutoff, Instant now) {
    var deleted = notificationRepository.deleteHistoryBefore(cutoff, now);
    log.info("Deleted {} read or resolved Notifications older than {}.", deleted, cutoff);
  }

  private void deleteHidden(List<Notification> batch, Instant cutoff) {
    var hidden =
        notificationReads.visibilities(batch).entrySet().stream()
            .filter(decided -> decided.getValue() == NotificationVisibility.HIDDEN)
            .map(Map.Entry::getKey)
            .toList();
    if (hidden.isEmpty()) {
      return;
    }

    transactionTemplate.executeWithoutResult(
        _ -> {
          var deleted = notificationRepository.deleteUnreadNewsBefore(hidden, cutoff);
          log.info("Deleted {} hidden unread Notifications older than {}.", deleted, cutoff);
        });
  }
}
