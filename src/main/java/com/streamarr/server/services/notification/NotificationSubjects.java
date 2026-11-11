package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.SubjectNotification;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How the Notifications about one kind of subject are read with their subjects and guarded (ADR
 * 0063 §The guard). Each {@link com.streamarr.server.domain.notification.NotificationSubject} has
 * one, and {@link NotificationReads} hands each Notification to the one of its kind's subject.
 */
interface NotificationSubjects {

  /**
   * The Notifications that the guard shows their recipients, each with its subject, in the order
   * given. A Notification whose subject was deleted meanwhile is gone with it.
   *
   * @throws AuthorizationUnavailableException when the guard cannot decide a Notification
   */
  List<SubjectNotification> shown(List<Notification> notifications);

  /**
   * What the guard decides for each Notification whose subject still exists, keyed by the
   * Notification's id.
   */
  Map<UUID, NotificationVisibility> visibilities(List<Notification> notifications);
}
