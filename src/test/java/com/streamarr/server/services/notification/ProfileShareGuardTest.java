package com.streamarr.server.services.notification;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Profile Share Guard Tests")
class ProfileShareGuardTest {

  private static final UUID RECIPIENT = UUID.randomUUID();
  private static final UUID PROFILE = UUID.randomUUID();
  private static final UUID HOUSEHOLD = UUID.randomUUID();

  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(defaultIdentityBuilder().build());
  private final ProfileShareGuard guard = new ProfileShareGuard(authorization);

  @ParameterizedTest(name = "Profile {0}, Household {1} -> {2}")
  @MethodSource("decisions")
  @DisplayName(
      "Should show the Notification when the recipient may view either the Profile's or the"
          + " Household's administration")
  void
      shouldShowTheNotificationWhenTheRecipientMayViewEitherTheProfilesOrTheHouseholdsAdministration(
          Answer profile, Answer household, NotificationVisibility expected) {
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewProfileAdministration(var profileId) when profileId.equals(PROFILE) ->
                  profile.decision();
              case Intent.ViewHouseholdAdministration(var householdId)
                  when householdId.equals(HOUSEHOLD) ->
                  household.decision();
              default -> Answer.DENIED.decision();
            });

    assertThat(guard.visibility(RECIPIENT, PROFILE, HOUSEHOLD)).isEqualTo(expected);
  }

  @Test
  @DisplayName("Should decide for the recipient Account when another Account may view the share")
  void shouldDecideForTheRecipientAccountWhenAnotherAccountMayViewTheShare() {
    authorization.decideForAccountWith(
        accountId -> accountId.equals(RECIPIENT) ? Answer.DENIED.decision() : null);

    assertThat(guard.visibility(RECIPIENT, PROFILE, HOUSEHOLD))
        .isEqualTo(NotificationVisibility.HIDDEN);
  }

  private static Stream<Arguments> decisions() {
    return Stream.of(
        Arguments.of(Answer.ALLOWED, Answer.ALLOWED, NotificationVisibility.SHOWN),
        Arguments.of(Answer.ALLOWED, Answer.DENIED, NotificationVisibility.SHOWN),
        Arguments.of(Answer.ALLOWED, Answer.FAILED, NotificationVisibility.SHOWN),
        Arguments.of(Answer.DENIED, Answer.ALLOWED, NotificationVisibility.SHOWN),
        Arguments.of(Answer.FAILED, Answer.ALLOWED, NotificationVisibility.SHOWN),
        Arguments.of(Answer.DENIED, Answer.DENIED, NotificationVisibility.HIDDEN),
        Arguments.of(Answer.DENIED, Answer.FAILED, NotificationVisibility.UNDECIDED),
        Arguments.of(Answer.FAILED, Answer.DENIED, NotificationVisibility.UNDECIDED),
        Arguments.of(Answer.FAILED, Answer.FAILED, NotificationVisibility.UNDECIDED));
  }

  private enum Answer {
    ALLOWED,
    DENIED,
    FAILED;

    Decision<AuthorizationUnit> decision() {
      return switch (this) {
        case ALLOWED -> new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
        case DENIED -> new Decision.Denied<>(Decision.DenialReason.POLICY);
        case FAILED -> new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE);
      };
    }
  }
}
