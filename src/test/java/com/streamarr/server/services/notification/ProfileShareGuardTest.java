package com.streamarr.server.services.notification;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.notification.GuardedNotification;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.util.List;
import java.util.Map;
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
    var notification = guarded(RECIPIENT, PROFILE, HOUSEHOLD);

    assertThat(guard.visibilities(List.of(notification)))
        .containsExactly(Map.entry(notification.notificationId(), expected));
  }

  @Test
  @DisplayName("Should decide for the recipient Account when another Account may view the share")
  void shouldDecideForTheRecipientAccountWhenAnotherAccountMayViewTheShare() {
    authorization.decideForAccountWith(
        accountId -> accountId.equals(RECIPIENT) ? Answer.DENIED.decision() : null);
    var notification = guarded(RECIPIENT, PROFILE, HOUSEHOLD);

    assertThat(guard.visibilities(List.of(notification)))
        .containsExactly(Map.entry(notification.notificationId(), NotificationVisibility.HIDDEN));
  }

  @Test
  @DisplayName(
      "Should decide each Notification of a batch on its own when they name different shares and"
          + " recipients")
  void shouldDecideEachNotificationOfABatchOnItsOwnWhenTheyNameDifferentSharesAndRecipients() {
    var otherRecipient = UUID.randomUUID();
    authorization
        .decideForAccountWith(
            accountId -> accountId.equals(otherRecipient) ? Answer.DENIED.decision() : null)
        .decideUnitWith(
            intent ->
                intent.equals(new Intent.ViewHouseholdAdministration(HOUSEHOLD))
                    ? Answer.ALLOWED.decision()
                    : Answer.DENIED.decision());
    var shown = guarded(RECIPIENT, PROFILE, HOUSEHOLD);
    var sameShareOtherRecipient = guarded(otherRecipient, PROFILE, HOUSEHOLD);
    var otherHousehold = guarded(RECIPIENT, UUID.randomUUID(), UUID.randomUUID());
    var sameHouseholdOtherProfile = guarded(RECIPIENT, UUID.randomUUID(), HOUSEHOLD);

    assertThat(
            guard.visibilities(
                List.of(shown, sameShareOtherRecipient, otherHousehold, sameHouseholdOtherProfile)))
        .containsOnly(
            Map.entry(shown.notificationId(), NotificationVisibility.SHOWN),
            Map.entry(sameShareOtherRecipient.notificationId(), NotificationVisibility.HIDDEN),
            Map.entry(otherHousehold.notificationId(), NotificationVisibility.HIDDEN),
            Map.entry(sameHouseholdOtherProfile.notificationId(), NotificationVisibility.SHOWN));
  }

  @Test
  @DisplayName("Should answer nothing when the batch holds no Notification")
  void shouldAnswerNothingWhenTheBatchHoldsNoNotification() {
    assertThat(guard.visibilities(List.of())).isEmpty();
  }

  private static GuardedNotification guarded(UUID recipient, UUID profileId, UUID householdId) {
    return new GuardedNotification(UUID.randomUUID(), recipient, profileId, householdId);
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
