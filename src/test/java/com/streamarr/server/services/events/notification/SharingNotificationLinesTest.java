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
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Sharing Notification Lines Tests")
class SharingNotificationLinesTest {

  private static final String RIGHT_TO_LEFT_OVERRIDE = Character.toString(0x202E);
  private static final Optional<RequestResolution> WAITING = Optional.empty();

  @ParameterizedTest(name = "{0}")
  @MethodSource("lines")
  @DisplayName(
      "Should word the line from the Profile's name it was told and how it resolved when it words"
          + " each sharing kind")
  void shouldWordTheLineFromTheProfilesNameItWasToldWhenItWordsEachSharingKind(
      String description,
      NotificationSnapshot snapshot,
      Optional<RequestResolution> resolution,
      String line) {
    assertThat(snapshot.line(resolution)).isEqualTo(line);
  }

  static Stream<Arguments> lines() {
    return Stream.of(
        Arguments.of(
            "a waiting offer",
            offered(Optional.empty()),
            WAITING,
            "“Kai” was offered to your Household."),
        Arguments.of(
            "an accepted offer",
            offered(Optional.of(ProfileShareAnswer.ACCEPTED)),
            Optional.of(RequestResolution.DECIDED),
            "The offer of “Kai” to your Household was accepted."),
        Arguments.of(
            "a declined offer",
            offered(Optional.of(ProfileShareAnswer.REJECTED)),
            Optional.of(RequestResolution.DECIDED),
            "The offer of “Kai” to your Household was declined."),
        Arguments.of(
            "a withdrawn offer",
            offered(Optional.empty()),
            Optional.of(RequestResolution.WITHDRAWN),
            "The offer of “Kai” to your Household was withdrawn."),
        Arguments.of(
            "an expired offer",
            offered(Optional.empty()),
            Optional.of(RequestResolution.EXPIRED),
            "The offer of “Kai” to your Household expired."),
        Arguments.of(
            "an accepted answer",
            new ProfileShareAnsweredSnapshot("Kai", ProfileShareAnswer.ACCEPTED),
            WAITING,
            "The offer of “Kai” was accepted."),
        Arguments.of(
            "a declined answer",
            new ProfileShareAnsweredSnapshot("Kai", ProfileShareAnswer.REJECTED),
            WAITING,
            "The offer of “Kai” was declined."),
        Arguments.of(
            "an end by the Household's side",
            new ProfileShareEndedSnapshot("Kai", ProfileShareEndedBy.HOUSEHOLD_SIDE),
            WAITING,
            "A Household stopped sharing “Kai”."),
        Arguments.of(
            "an end by the offering side",
            new ProfileShareEndedSnapshot("Kai", ProfileShareEndedBy.OFFERING_SIDE),
            WAITING,
            "“Kai” is no longer shared with your Household."),
        Arguments.of(
            "an end by the server",
            new ProfileShareEndedSnapshot("Kai", ProfileShareEndedBy.SERVER),
            WAITING,
            "The share of “Kai” was ended."));
  }

  @Test
  @DisplayName(
      "Should quote the Profile's name and drop its control and format characters when the name"
          + " tries to end the line")
  void
      shouldQuoteTheProfilesNameAndDropItsControlAndFormatCharactersWhenTheNameTriesToEndTheLine() {
    var snapshot =
        new ProfileShareOfferedSnapshot(
            "Kai" + RIGHT_TO_LEFT_OVERRIDE + ".\nReset your password", Optional.empty());

    assertThat(snapshot.line(WAITING))
        .isEqualTo("“Kai.Reset your password” was offered to your Household.");
  }

  private static ProfileShareOfferedSnapshot offered(Optional<ProfileShareAnswer> answer) {
    return new ProfileShareOfferedSnapshot("Kai", answer);
  }
}
