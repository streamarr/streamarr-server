package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.SubjectReference;
import java.util.List;

/**
 * The kinds the identity domain tells about Profile management (ADR 0063 §The Profile management
 * kinds). An invitation's request and its answer take the ProfileManager invitation as their
 * subject, and a removal takes the Profile.
 */
public final class ProfileManagementNotificationKinds {

  /** Invitation received: the recipient may accept an invitation to manage a Profile. */
  public static final NotificationKind PROFILE_MANAGER_INVITATION_RECEIVED =
      NotificationKind.builder()
          .name("PROFILE_MANAGER_INVITATION_RECEIVED")
          .form(NotificationForm.REQUEST)
          .subjectType(SubjectReference.PROFILE_MANAGER_INVITATION)
          .snapshotType(ProfileManagerInvitationReceivedSnapshot.class)
          .build();

  /** Invitation answered: the Account the recipient invited accepted or declined. */
  public static final NotificationKind PROFILE_MANAGER_INVITATION_ANSWERED =
      NotificationKind.builder()
          .name("PROFILE_MANAGER_INVITATION_ANSWERED")
          .form(NotificationForm.NEWS)
          .subjectType(SubjectReference.PROFILE_MANAGER_INVITATION)
          .snapshotType(ProfileManagerInvitationAnsweredSnapshot.class)
          .build();

  /**
   * Management removed: someone else removed the recipient's direct management of a Profile. A
   * later removal while it is unread replaces its time and the Profile's name.
   */
  public static final NotificationKind PROFILE_MANAGEMENT_REMOVED =
      NotificationKind.builder()
          .name("PROFILE_MANAGEMENT_REMOVED")
          .form(NotificationForm.NEWS)
          .subjectType(SubjectReference.PROFILE)
          .snapshotType(ProfileManagementRemovedSnapshot.class)
          .build();

  public static final List<NotificationKind> ALL =
      List.of(
          PROFILE_MANAGER_INVITATION_RECEIVED,
          PROFILE_MANAGER_INVITATION_ANSWERED,
          PROFILE_MANAGEMENT_REMOVED);

  private ProfileManagementNotificationKinds() {}
}
