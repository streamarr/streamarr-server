package com.streamarr.server.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Profile Share Line Tests")
class ProfileShareLineTest {

  private static final String RIGHT_TO_LEFT_OVERRIDE = Character.toString(0x202E);

  @ParameterizedTest(name = "{0}")
  @MethodSource("lines")
  @DisplayName("Should word the line from the Profile's name alone when it words each kind")
  void shouldWordTheLineFromTheProfilesNameAloneWhenItWordsEachKind(
      String description, ProfileShareLine line, String text) {
    assertThat(line.text()).isEqualTo(text);
  }

  static Stream<Arguments> lines() {
    return Stream.of(
        Arguments.of("offered", offered("Kai"), "“Kai” was offered to your Household."),
        Arguments.of(
            "accepted", answered(ProfileShareAnswer.ACCEPTED), "The offer of “Kai” was accepted."),
        Arguments.of(
            "declined", answered(ProfileShareAnswer.REJECTED), "The offer of “Kai” was declined."),
        Arguments.of(
            "ended by the Household's side",
            ended(ProfileShareEndedBy.HOUSEHOLD_SIDE),
            "A Household stopped sharing “Kai”."),
        Arguments.of(
            "ended by the offering side",
            ended(ProfileShareEndedBy.OFFERING_SIDE),
            "“Kai” is no longer shared with your Household."),
        Arguments.of(
            "ended by the server",
            ended(ProfileShareEndedBy.SERVER),
            "The share of “Kai” was ended."));
  }

  @Test
  @DisplayName(
      "Should quote the Profile's name and drop its control and format characters when the name"
          + " tries to end the line")
  void
      shouldQuoteTheProfilesNameAndDropItsControlAndFormatCharactersWhenTheNameTriesToEndTheLine() {
    var line = offered("Kai" + RIGHT_TO_LEFT_OVERRIDE + ".\nReset your password");

    assertThat(line.text()).isEqualTo("“Kai.Reset your password” was offered to your Household.");
  }

  private static ProfileShareLine offered(String profileName) {
    return ProfileShareLine.builder()
        .kind(NotificationKind.PROFILE_SHARE_OFFERED)
        .profileName(profileName)
        .build();
  }

  private static ProfileShareLine answered(ProfileShareAnswer answer) {
    return ProfileShareLine.builder()
        .kind(NotificationKind.PROFILE_SHARE_ANSWERED)
        .profileName("Kai")
        .answer(answer)
        .build();
  }

  private static ProfileShareLine ended(ProfileShareEndedBy endedBy) {
    return ProfileShareLine.builder()
        .kind(NotificationKind.PROFILE_SHARE_ENDED)
        .profileName("Kai")
        .endedBy(endedBy)
        .build();
  }
}
