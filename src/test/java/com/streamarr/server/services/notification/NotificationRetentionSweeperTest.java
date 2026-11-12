package com.streamarr.server.services.notification;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareSide;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeNotificationRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeServerSettingsRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention's rule for a guard decision that fails (ADR 0063 §Retention): the unread news stays for
 * the next sweep, while the news that the guard hides from another recipient goes.
 */
@Tag("UnitTest")
@DisplayName("Notification Retention Sweeper Tests")
class NotificationRetentionSweeperTest {

  private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
  private static final Instant PAST_THE_PERIOD = NOW.minus(Duration.ofDays(91));
  private static final UUID UNDECIDED_RECIPIENT = UUID.randomUUID();
  private static final UUID HIDDEN_RECIPIENT = UUID.randomUUID();

  private final FakeNotificationRepository notifications = new FakeNotificationRepository();
  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(defaultIdentityBuilder().build());
  private final NotificationRetentionSweeper sweeper =
      new NotificationRetentionSweeper(
          notifications,
          new NotificationReads(
              new ShareNotifications(
                  shares, new ProfileShareGuard(authorization), Clock.fixed(NOW, ZoneOffset.UTC))),
          new ServerSettingsService(authorization, new FakeServerSettingsRepository()),
          new TransactionTemplate(new FakeTransactionManager()),
          Clock.fixed(NOW, ZoneOffset.UTC),
          500);

  @Test
  @DisplayName(
      "Should keep aged unread news when the guard cannot decide for its recipient, and delete the"
          + " news it hides")
  void shouldKeepAgedUnreadNewsWhenTheGuardCannotDecideForItsRecipient() {
    var share = shares.share(UUID.randomUUID(), UUID.randomUUID(), false);
    authorization.decideForAccountWith(
        accountId ->
            accountId.equals(UNDECIDED_RECIPIENT)
                ? new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE)
                : new Decision.Denied<>(Decision.DenialReason.POLICY));
    var undecided = notifications.save(agedNewsFor(UNDECIDED_RECIPIENT, share));
    notifications.save(agedNewsFor(HIDDEN_RECIPIENT, share));

    sweeper.sweep();

    assertThat(notifications.findAll())
        .extracting(Notification::getId)
        .containsExactly(undecided.getId());
  }

  private static Notification agedNewsFor(UUID recipientAccountId, ProfileHouseholdShare share) {
    return Notification.builder()
        .recipientAccountId(recipientAccountId)
        .kind(NotificationKind.PROFILE_SHARE_ANSWERED)
        .form(NotificationForm.NEWS)
        .shareId(share.getId())
        .profileName("Kai")
        .addressedTo(ProfileShareSide.OFFERING_SIDE)
        .latestEventAt(PAST_THE_PERIOD)
        .build();
  }
}
