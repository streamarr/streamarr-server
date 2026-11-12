package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareFacts;

/**
 * The sharing kinds (ADR 0063 §The sharing kinds). A later event takes the place of the stored
 * facts: a sharing Notification keeps the Profile's name as it was at its latest event, and an
 * offer has one answer and a share ends once. The side it was addressed to stays as it was written.
 */
class ProfileShareKinds implements NotificationKinds<NotificationKind, ProfileShareFacts> {

  @Override
  public NotificationForm formOf(NotificationKind kind) {
    return kind.getForm();
  }

  @Override
  public ProfileShareFacts combine(
      NotificationKind kind, ProfileShareFacts stored, ProfileShareFacts later) {
    return later.addressedAs(stored);
  }
}
