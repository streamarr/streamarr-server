package com.streamarr.server.services.events.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.notification.NotificationSnapshot;
import com.streamarr.server.domain.notification.RequestResolution;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Profile Management Notification Lines Tests")
class ProfileManagementNotificationLinesTest {

  private static final String RIGHT_TO_LEFT_OVERRIDE = Character.toString(0x202E);
  private static final String CONTROLLED_NAME =
      "Alex" + RIGHT_TO_LEFT_OVERRIDE + ".\nReset your password";
  private static final Optional<RequestResolution> WAITING = Optional.empty();

  @Test
  @DisplayName("Should say the Account is invited when the request waits")
  void shouldSayTheAccountIsInvitedWhenTheRequestWaits() {
    var snapshot = new ProfileManagerInvitationReceivedSnapshot("Alex", Optional.empty());

    assertThat(snapshot.line(WAITING)).isEqualTo("You were invited to manage “Alex”.");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("resolutions")
  @DisplayName("Should say how the invitation ended when the request resolves")
  void shouldSayHowTheInvitationEndedWhenTheRequestResolves(
      String description,
      Optional<ProfileManagerInvitationAnswer> answer,
      RequestResolution resolution,
      String line) {
    var snapshot = new ProfileManagerInvitationReceivedSnapshot("Alex", answer);

    assertThat(snapshot.line(Optional.of(resolution))).isEqualTo(line);
  }

  static Stream<Arguments> resolutions() {
    return Stream.of(
        Arguments.of(
            "accepted",
            Optional.of(ProfileManagerInvitationAnswer.ACCEPTED),
            RequestResolution.DECIDED,
            "The invitation to manage “Alex” was accepted."),
        Arguments.of(
            "declined",
            Optional.of(ProfileManagerInvitationAnswer.DECLINED),
            RequestResolution.DECIDED,
            "The invitation to manage “Alex” was declined."),
        Arguments.of(
            "withdrawn",
            Optional.empty(),
            RequestResolution.WITHDRAWN,
            "The invitation to manage “Alex” was withdrawn."),
        Arguments.of(
            "expired",
            Optional.empty(),
            RequestResolution.EXPIRED,
            "The invitation to manage “Alex” expired."));
  }

  @ParameterizedTest
  @CsvSource({"ACCEPTED, accepted", "DECLINED, declined"})
  @DisplayName("Should say how the invited Account answered when the inviter is told")
  void shouldSayHowTheInvitedAccountAnsweredWhenTheInviterIsTold(
      ProfileManagerInvitationAnswer answer, String answered) {
    var snapshot = new ProfileManagerInvitationAnsweredSnapshot("Alex", answer);

    assertThat(snapshot.line(WAITING))
        .isEqualTo("Your invitation to manage “Alex” was %s.".formatted(answered));
  }

  @Test
  @DisplayName(
      "Should say the Account no longer manages the Profile when its management is removed")
  void shouldSayTheAccountNoLongerManagesTheProfileWhenItsManagementIsRemoved() {
    var snapshot = new ProfileManagementRemovedSnapshot("Alex");

    assertThat(snapshot.line(WAITING)).isEqualTo("You no longer manage “Alex”.");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("snapshotsOfAControlledName")
  @DisplayName("Should quote the Profile's name when it holds control characters")
  void shouldQuoteTheProfilesNameWhenItHoldsControlCharacters(
      String kind, NotificationSnapshot snapshot, String line) {
    assertThat(snapshot.line(WAITING)).isEqualTo(line);
  }

  static Stream<Arguments> snapshotsOfAControlledName() {
    return Stream.of(
        Arguments.of(
            "an invitation's request",
            new ProfileManagerInvitationReceivedSnapshot(CONTROLLED_NAME, Optional.empty()),
            "You were invited to manage “Alex.Reset your password”."),
        Arguments.of(
            "an invitation's answer",
            new ProfileManagerInvitationAnsweredSnapshot(
                CONTROLLED_NAME, ProfileManagerInvitationAnswer.ACCEPTED),
            "Your invitation to manage “Alex.Reset your password” was accepted."),
        Arguments.of(
            "a removal",
            new ProfileManagementRemovedSnapshot(CONTROLLED_NAME),
            "You no longer manage “Alex.Reset your password”."));
  }
}
