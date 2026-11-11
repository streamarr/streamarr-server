package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.UnreadShareNews;
import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention (ADR 0063 §Retention): deletes the history whose latest event is older than the
 * retention period, the news that its recipient read and the requests that resolved, and the unread
 * news that the guard hides from its recipient after the same period. Unread news that the guard
 * shows, waiting requests, and news whose guard decision fails stay.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class NotificationRetentionSweeper {

  private final NotificationRepository notificationRepository;
  private final ProfileShareGuard guard;
  private final ServerSettingsService serverSettingsService;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

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
    var hidden =
        notificationRepository.findUnreadNewsBefore(cutoff).stream()
            .filter(this::isHidden)
            .map(UnreadShareNews::notificationId)
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

  private void deleteHistory(Instant cutoff, Instant now) {
    var deleted = notificationRepository.deleteHistoryBefore(cutoff, now);
    log.info("Deleted {} read or resolved Notifications older than {}.", deleted, cutoff);
  }

  private boolean isHidden(UnreadShareNews news) {
    return guard.visibility(news.recipientAccountId(), news.profileId(), news.householdId())
        == NotificationVisibility.HIDDEN;
  }
}
