package com.streamarr.server.repositories.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.NotificationSnapshot;
import com.streamarr.server.services.events.notification.ProfileManagementNotificationKinds;
import com.streamarr.server.services.events.notification.ProfileManagementRemovedSnapshot;
import com.streamarr.server.services.events.notification.ProfileManagerInvitationAnswer;
import com.streamarr.server.services.events.notification.ProfileManagerInvitationAnsweredSnapshot;
import com.streamarr.server.services.events.notification.ProfileManagerInvitationReceivedSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareAnswer;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareEndedBy;
import com.streamarr.server.services.events.notification.ProfileShareEndedSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareOfferedSnapshot;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.HouseholdNewsSnapshot;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.ScanOrRefreshFinishedSnapshot;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.UnmatchedItemsSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Snapshot JSON Tests")
class SnapshotJsonTest {

  private static final String NAME = "Kai \"Junior\" Ølund";

  private static final Map<NotificationKind, List<NotificationSnapshot>> SAMPLES =
      Map.of(
          SharingNotificationKinds.PROFILE_SHARE_OFFERED,
          List.of(
              new ProfileShareOfferedSnapshot(NAME, Optional.empty()),
              new ProfileShareOfferedSnapshot(NAME, Optional.of(ProfileShareAnswer.ACCEPTED))),
          SharingNotificationKinds.PROFILE_SHARE_ANSWERED,
          List.of(new ProfileShareAnsweredSnapshot(NAME, ProfileShareAnswer.REJECTED)),
          SharingNotificationKinds.PROFILE_SHARE_ENDED,
          List.of(new ProfileShareEndedSnapshot(NAME, ProfileShareEndedBy.HOUSEHOLD_SIDE)),
          ProfileManagementNotificationKinds.PROFILE_MANAGER_INVITATION_RECEIVED,
          List.of(
              new ProfileManagerInvitationReceivedSnapshot(NAME, Optional.empty()),
              new ProfileManagerInvitationReceivedSnapshot(
                  NAME, Optional.of(ProfileManagerInvitationAnswer.DECLINED))),
          ProfileManagementNotificationKinds.PROFILE_MANAGER_INVITATION_ANSWERED,
          List.of(
              new ProfileManagerInvitationAnsweredSnapshot(
                  NAME, ProfileManagerInvitationAnswer.ACCEPTED)),
          ProfileManagementNotificationKinds.PROFILE_MANAGEMENT_REMOVED,
          List.of(new ProfileManagementRemovedSnapshot(NAME)),
          SpecifiedOnlyNotificationKinds.SCAN_OR_REFRESH_FINISHED,
          List.of(
              ScanOrRefreshFinishedSnapshot.builder()
                  .libraryName(NAME)
                  .titlesAdded(3)
                  .titlesRemoved(0)
                  .filesSkipped(12)
                  .build()),
          SpecifiedOnlyNotificationKinds.UNMATCHED_ITEMS,
          List.of(new UnmatchedItemsSnapshot(NAME, 14)),
          SpecifiedOnlyNotificationKinds.HOUSEHOLD_NEWS,
          List.of(new HouseholdNewsSnapshot(NAME)));

  // The kinds the server tells, and the kinds that tests define to show the model fits them.
  static Stream<Arguments> everyKindsSnapshots() {
    return Stream.of(
            SharingNotificationKinds.ALL,
            ProfileManagementNotificationKinds.ALL,
            SpecifiedOnlyNotificationKinds.ALL)
        .flatMap(List::stream)
        .flatMap(
            kind ->
                samplesOf(kind).stream()
                    .map(snapshot -> Arguments.of(Named.of(kind.name(), kind), snapshot)));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("everyKindsSnapshots")
  @DisplayName("Should read back every snapshot unchanged when it is written")
  void shouldReadBackEverySnapshotUnchangedWhenItIsWritten(
      NotificationKind kind, NotificationSnapshot snapshot) {
    var document = SnapshotJson.write(snapshot);

    assertThat(SnapshotJson.read(kind, document)).isEqualTo(snapshot);
  }

  private static List<NotificationSnapshot> samplesOf(NotificationKind kind) {
    return Optional.ofNullable(SAMPLES.get(kind))
        .orElseThrow(() -> new AssertionError("No sample snapshot of " + kind.name()));
  }
}
