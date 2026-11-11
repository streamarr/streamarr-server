package com.streamarr.server.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
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

  private static final Optional<String> SAM = Optional.of("Sam");
  private static final Optional<String> RIVERSIDE = Optional.of("Riverside");

  @ParameterizedTest(name = "{0}")
  @MethodSource("lines")
  @DisplayName("Should name only the parties its reader may read when it words the line")
  void shouldNameOnlyThePartiesItsReaderMayReadWhenItWordsTheLine(
      String description, ProfileShareLine line, String text) {
    assertThat(line.text()).isEqualTo(text);
  }

  static Stream<Arguments> lines() {
    return Stream.of(
        Arguments.of(
            "offered, with the offerer",
            offered(SAM),
            "“Sam” offered the Profile “Kai” to your Household."),
        Arguments.of(
            "offered, once the offerer is hidden",
            offered(Optional.empty()),
            "The Profile “Kai” was offered to your Household."),
        Arguments.of(
            "accepted, with the Household",
            answered(ProfileShareAnswer.ACCEPTED, RIVERSIDE),
            "“Riverside” accepted the Profile “Kai”."),
        Arguments.of(
            "rejected, with the Household",
            answered(ProfileShareAnswer.REJECTED, RIVERSIDE),
            "“Riverside” rejected the Profile “Kai”."),
        Arguments.of(
            "accepted, without the Household",
            answered(ProfileShareAnswer.ACCEPTED, Optional.empty()),
            "The offer of the Profile “Kai” was accepted."),
        Arguments.of(
            "rejected, without the Household",
            answered(ProfileShareAnswer.REJECTED, Optional.empty()),
            "The offer of the Profile “Kai” was rejected."),
        Arguments.of(
            "ended by the Household, told to the offering side",
            ended(ProfileShareEndedBy.HOUSEHOLD_SIDE, RIVERSIDE, false),
            "“Riverside” stopped sharing the Profile “Kai”."),
        Arguments.of(
            "ended by the Household, once the Household is hidden",
            ended(ProfileShareEndedBy.HOUSEHOLD_SIDE, Optional.empty(), false),
            "A Household stopped sharing the Profile “Kai”."),
        Arguments.of(
            "ended by the offering side, told to the Household",
            ended(ProfileShareEndedBy.OFFERING_SIDE, Optional.empty(), true),
            "The Profile “Kai” is no longer shared with your Household."),
        Arguments.of(
            "ended by the server, told to the offering side",
            ended(ProfileShareEndedBy.SERVER, RIVERSIDE, false),
            "The Profile “Kai” is no longer shared with “Riverside”."),
        Arguments.of(
            "ended by the server, told to the Household",
            ended(ProfileShareEndedBy.SERVER, Optional.empty(), true),
            "The Profile “Kai” is no longer shared with your Household."),
        Arguments.of(
            "ended by the server, told to an offering side that may not read the Household",
            ended(ProfileShareEndedBy.SERVER, Optional.empty(), false),
            "The Profile “Kai” is no longer shared with a Household."),
        Arguments.of(
            "offered, told to a reader outside the Household",
            offered(SAM).toBuilder().toReadersHousehold(false).build(),
            "“Sam” offered the Profile “Kai” to a Household."));
  }

  @Test
  @DisplayName(
      "Should quote each name and drop its control and format characters when a name tries to"
          + " end the line")
  void shouldQuoteEachNameAndDropItsControlAndFormatCharactersWhenANameTriesToEndTheLine() {
    var line =
        offered(Optional.of("Sam‮. Your Household was deleted")).toBuilder()
            .profileName("Kai.\nReset your password")
            .build();

    assertThat(line.text())
        .isEqualTo(
            "“Sam. Your Household was deleted” offered the Profile “Kai.Reset your password” to"
                + " your Household.");
  }

  private static ProfileShareLine offered(Optional<String> offererName) {
    return ProfileShareLine.builder()
        .kind(NotificationKind.PROFILE_SHARE_OFFERED)
        .profileName("Kai")
        .offererName(offererName)
        .householdName(Optional.empty())
        .toReadersHousehold(true)
        .build();
  }

  private static ProfileShareLine answered(
      ProfileShareAnswer answer, Optional<String> householdName) {
    return ProfileShareLine.builder()
        .kind(NotificationKind.PROFILE_SHARE_ANSWERED)
        .profileName("Kai")
        .offererName(Optional.empty())
        .householdName(householdName)
        .answer(answer)
        .build();
  }

  private static ProfileShareLine ended(
      ProfileShareEndedBy endedBy, Optional<String> householdName, boolean toReadersHousehold) {
    return ProfileShareLine.builder()
        .kind(NotificationKind.PROFILE_SHARE_ENDED)
        .profileName("Kai")
        .offererName(Optional.empty())
        .householdName(householdName)
        .endedBy(endedBy)
        .toReadersHousehold(toReadersHousehold)
        .build();
  }
}
