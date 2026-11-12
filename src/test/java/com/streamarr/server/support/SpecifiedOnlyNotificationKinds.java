package com.streamarr.server.support;

import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.domain.notification.RequestAnswer;
import com.streamarr.server.domain.notification.RequestResolution;
import com.streamarr.server.services.events.notification.NotificationKind;
import com.streamarr.server.services.events.notification.NotificationSnapshot;
import com.streamarr.server.services.events.notification.SubjectReference;
import java.util.Optional;
import lombok.NonNull;

/**
 * Kinds that ADR 0063 specifies for later builds, defined in test code alone to show that the
 * module's model fits them, and a made-up kind of news about a Household, which no kind built or
 * specified has yet. No domain tells them, so the tests tell them as a domain would.
 */
public final class SpecifiedOnlyNotificationKinds {

  /** Management removed: the recipient no longer manages the Profile. */
  public static final NotificationKind MANAGEMENT_REMOVED =
      NotificationKind.builder()
          .name("PROFILE_MANAGEMENT_REMOVED")
          .form(NotificationForm.NEWS)
          .subjectType(SubjectReference.PROFILE)
          .snapshotType(ManagementRemovedSnapshot.class)
          .build();

  /** Invitation received: the recipient may accept an invitation to manage the Profile. */
  public static final NotificationKind INVITATION_RECEIVED =
      NotificationKind.builder()
          .name("PROFILE_MANAGER_INVITATION_RECEIVED")
          .form(NotificationForm.REQUEST)
          .subjectType(SubjectReference.PROFILE_MANAGER_INVITATION)
          .snapshotType(InvitationReceivedSnapshot.class)
          .build();

  /** Made-up news about a Household. */
  public static final NotificationKind HOUSEHOLD_NEWS =
      NotificationKind.builder()
          .name("TEST_HOUSEHOLD_NEWS")
          .form(NotificationForm.NEWS)
          .subjectType(SubjectReference.HOUSEHOLD)
          .snapshotType(HouseholdNewsSnapshot.class)
          .build();

  private SpecifiedOnlyNotificationKinds() {}

  /** What the removed Account was told: the Profile's name at the removal. */
  public record ManagementRemovedSnapshot(@NonNull String profileName)
      implements NotificationSnapshot {

    @Override
    public String line(Optional<RequestResolution> resolution) {
      return "You no longer manage %s.".formatted(profileName);
    }
  }

  /** What the invited Account was told, and the answer once it decides. */
  public record InvitationReceivedSnapshot(
      @NonNull String profileName, @NonNull Optional<InvitationAnswer> answer)
      implements NotificationSnapshot {

    @Override
    public String line(Optional<RequestResolution> resolution) {
      return "You were invited to manage %s.".formatted(profileName);
    }
  }

  /** What a Household's Account was told. */
  public record HouseholdNewsSnapshot(@NonNull String householdName)
      implements NotificationSnapshot {

    @Override
    public String line(Optional<RequestResolution> resolution) {
      return "Something happened in %s.".formatted(householdName);
    }
  }

  /** How an invited Account decides an invitation. */
  public enum InvitationAnswer implements RequestAnswer {
    ACCEPTED,
    DECLINED
  }
}
