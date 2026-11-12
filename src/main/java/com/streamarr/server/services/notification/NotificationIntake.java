package com.streamarr.server.services.notification;

import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.events.notification.DismissNotificationsEvent;
import com.streamarr.server.services.events.notification.ResolveRequestsEvent;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.notification.events.ChangedNotification;
import com.streamarr.server.services.notification.events.NotificationsChangedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The module's intake (ADR 0063 §How the server builds it). It hears the events a domain publishes
 * inside the transaction that changes a subject, so what it keeps commits or rolls back with that
 * change, and it refuses an event published outside one. It decides nothing about a subject: the
 * publishing domain already decided who is told and what. Each operation that changes a
 * Notification announces the changes in one {@link NotificationsChangedEvent}.
 */
@Service
@RequiredArgsConstructor
public class NotificationIntake {

  private final NotificationRepository notificationRepository;
  private final ApplicationEventPublisher eventPublisher;

  @EventListener
  @Transactional(propagation = Propagation.MANDATORY)
  public void onTell(TellNotificationEvent tell) {
    announce(
        switch (tell.kind().form()) {
          case NEWS -> notificationRepository.tellNews(tell);
          case REQUEST -> notificationRepository.tellRequests(tell);
        });
  }

  @EventListener
  @Transactional(propagation = Propagation.MANDATORY)
  public void onResolve(ResolveRequestsEvent resolution) {
    announce(notificationRepository.resolve(resolution));
  }

  @EventListener
  @Transactional(propagation = Propagation.MANDATORY)
  public void onDismiss(DismissNotificationsEvent dismissal) {
    announce(notificationRepository.dismiss(dismissal.selector()));
  }

  private void announce(List<ChangedNotification> changed) {
    if (changed.isEmpty()) {
      return;
    }

    eventPublisher.publishEvent(new NotificationsChangedEvent(changed));
  }
}
