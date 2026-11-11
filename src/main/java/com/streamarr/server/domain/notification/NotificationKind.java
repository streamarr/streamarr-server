package com.streamarr.server.domain.notification;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What happened, for each kind of Notification the server sends (ADR 0063 §The kinds). The kind
 * fixes the Notification's form, its subject, its recipients and what it carries, and a
 * Notification keeps its kind for its whole life.
 */
@Getter
@RequiredArgsConstructor
public enum NotificationKind {
  /** A Profile is offered to a Household; its subject is the offer. */
  PROFILE_SHARE_OFFERED(NotificationForm.REQUEST, NotificationSubject.PROFILE_SHARE),
  /** A HouseholdAdmin or a ServerAdmin accepted or rejected an offer. */
  PROFILE_SHARE_ANSWERED(NotificationForm.NEWS, NotificationSubject.PROFILE_SHARE),
  /** An active share ended. */
  PROFILE_SHARE_ENDED(NotificationForm.NEWS, NotificationSubject.PROFILE_SHARE);

  private final NotificationForm form;
  private final NotificationSubject subject;
}
