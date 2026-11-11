package com.streamarr.server.services.notification;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.services.authorization.Decision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Share Notifications Tests")
class ShareNotificationsTest {

  private static final UUID SHOWN_RECIPIENT = UUID.randomUUID();
  private static final UUID UNDECIDED_RECIPIENT = UUID.randomUUID();

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(defaultIdentityBuilder().build());
  private final ShareNotifications notifications =
      new ShareNotifications(shares, new ProfileShareGuard(authorization));

  @Test
  @DisplayName(
      "Should refuse the read as authorization unavailable when the guard cannot decide one of its"
          + " Notifications")
  void shouldRefuseTheReadAsAuthorizationUnavailableWhenTheGuardCannotDecideANotification() {
    var share = shares.share(UUID.randomUUID(), UUID.randomUUID(), false);
    authorization.decideForAccountWith(
        accountId ->
            accountId.equals(UNDECIDED_RECIPIENT)
                ? new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE)
                : null);
    var batch = List.of(newsFor(SHOWN_RECIPIENT, share), newsFor(UNDECIDED_RECIPIENT, share));

    assertThatThrownBy(() -> notifications.shown(batch))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  private static Notification newsFor(UUID recipientAccountId, ProfileHouseholdShare share) {
    return Notification.builder()
        .id(UUID.randomUUID())
        .recipientAccountId(recipientAccountId)
        .kind(NotificationKind.PROFILE_SHARE_ANSWERED)
        .form(NotificationForm.NEWS)
        .shareId(share.getId())
        .profileName("Kai")
        .latestEventAt(Instant.now())
        .build();
  }
}
