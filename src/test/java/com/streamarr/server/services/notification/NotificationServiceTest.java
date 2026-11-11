package com.streamarr.server.services.notification;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static com.streamarr.server.fixtures.PaginationFixture.firstRows;
import static com.streamarr.server.fixtures.PaginationFixture.rowsAfter;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.SubjectNotification;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeNotificationRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.pagination.ListPage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The list and the count when the guard hides Notifications that their read rule's SQL filter let
 * through: the fake repository lets every Notification through, so the guard alone decides.
 */
@Tag("UnitTest")
@DisplayName("Notification Service Tests")
class NotificationServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
  private static final UUID SHOWN_HOUSEHOLD = UUID.randomUUID();
  private static final UUID HIDDEN_HOUSEHOLD = UUID.randomUUID();

  private final AuthenticatedIdentity identity = defaultIdentityBuilder().build();
  private final FakeNotificationRepository notifications = new FakeNotificationRepository();
  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeAuthorizationService authorization = new FakeAuthorizationService(identity);
  private final NotificationReads reads =
      new NotificationReads(new ShareNotifications(shares, new ProfileShareGuard(authorization)));
  private final NotificationService service =
      new NotificationService(
          authorization,
          new NotificationsList(notifications, reads),
          reads,
          notifications,
          Clock.fixed(NOW, ZoneOffset.UTC),
          new CapturingEventPublisher());

  @Test
  @DisplayName(
      "Should fill the page and count only what the guard shows when newer Notifications are hidden"
          + " from the Account")
  void shouldFillThePageAndCountOnlyWhatTheGuardShowsWhenNewerNotificationsAreHidden() {
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewHouseholdAdministration(var householdId)
                  when householdId.equals(HIDDEN_HOUSEHOLD) ->
                  new Decision.Denied<>(Decision.DenialReason.POLICY);
              case Intent.ViewProfileAdministration _ ->
                  new Decision.Denied<>(Decision.DenialReason.POLICY);
              default -> new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
            });
    var oldest = newsInto(SHOWN_HOUSEHOLD, Duration.ofMinutes(6));
    var older = newsInto(SHOWN_HOUSEHOLD, Duration.ofMinutes(5));
    var newestShown = newsInto(SHOWN_HOUSEHOLD, Duration.ofMinutes(4));
    for (var minutesAgo = 3; minutesAgo > 0; minutesAgo--) {
      newsInto(HIDDEN_HOUSEHOLD, Duration.ofMinutes(minutesAgo));
    }

    var first = service.notifications(identity, firstRows(2));

    assertThat(idsOf(first)).containsExactly(newestShown.getId(), older.getId());
    assertThat(first.hasNextPage()).isTrue();
    var next = service.notifications(identity, rowsAfter(2, first.rows().getLast()));
    assertThat(idsOf(next)).containsExactly(oldest.getId());
    assertThat(next.hasNextPage()).isFalse();
    assertThat(service.unreadNotificationCount(identity)).isEqualTo(3);
  }

  /** Unread news to the Account about a new share into the Household, its event the age ago. */
  private Notification newsInto(UUID householdId, Duration age) {
    var share = shares.share(UUID.randomUUID(), householdId, false);
    return notifications.save(
        Notification.builder()
            .recipientAccountId(identity.accountId())
            .kind(NotificationKind.PROFILE_SHARE_ANSWERED)
            .form(NotificationForm.NEWS)
            .shareId(share.getId())
            .profileName("Kai")
            .latestEventAt(NOW.minus(age))
            .build());
  }

  private static List<UUID> idsOf(ListPage<SubjectNotification> page) {
    return page.rows().stream().map(row -> row.item().notification().getId()).toList();
  }
}
