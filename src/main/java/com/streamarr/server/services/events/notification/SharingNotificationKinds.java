package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.NotificationForm;
import java.util.List;

/**
 * The kinds the identity domain tells about one Profile share (ADR 0063 §The sharing kinds). Each
 * takes the share as its subject, from the pending offer to the ended share.
 */
public final class SharingNotificationKinds {

  /** Offer waiting: a Profile was offered to the recipient's Household. */
  public static final NotificationKind PROFILE_SHARE_OFFERED =
      NotificationKind.builder()
          .name("PROFILE_SHARE_OFFERED")
          .form(NotificationForm.REQUEST)
          .subjectType(SubjectReference.PROFILE_SHARE)
          .snapshotType(ProfileShareOfferedSnapshot.class)
          .build();

  /** Offer answered: a Household accepted or rejected an offer of the offering side. */
  public static final NotificationKind PROFILE_SHARE_ANSWERED =
      NotificationKind.builder()
          .name("PROFILE_SHARE_ANSWERED")
          .form(NotificationForm.NEWS)
          .subjectType(SubjectReference.PROFILE_SHARE)
          .snapshotType(ProfileShareAnsweredSnapshot.class)
          .build();

  /** Share ended: an active share ended, and the recipient's side did not end it. */
  public static final NotificationKind PROFILE_SHARE_ENDED =
      NotificationKind.builder()
          .name("PROFILE_SHARE_ENDED")
          .form(NotificationForm.NEWS)
          .subjectType(SubjectReference.PROFILE_SHARE)
          .snapshotType(ProfileShareEndedSnapshot.class)
          .build();

  public static final List<NotificationKind> ALL =
      List.of(PROFILE_SHARE_OFFERED, PROFILE_SHARE_ANSWERED, PROFILE_SHARE_ENDED);

  private SharingNotificationKinds() {}
}
