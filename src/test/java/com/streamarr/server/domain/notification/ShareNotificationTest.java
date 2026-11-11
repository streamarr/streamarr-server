package com.streamarr.server.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Share Notification Tests")
class ShareNotificationTest {

  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @ParameterizedTest(name = "{0}")
  @EnumSource(ProfileShareStatus.class)
  @DisplayName("Should stay unread until it is read when it is news, whatever its share's status")
  void shouldStayUnreadUntilItIsReadWhenItIsNewsWhateverItsSharesStatus(ProfileShareStatus status) {
    var share = share(status, NOW.minusSeconds(60));

    var unread =
        new ShareNotification(Notification.builder().form(NotificationForm.NEWS).build(), share);
    var read =
        new ShareNotification(
            Notification.builder().form(NotificationForm.NEWS).readAt(NOW).build(), share);

    assertThat(unread.unreadAt(NOW)).isTrue();
    assertThat(unread.resolutionAt(NOW)).isEmpty();
    assertThat(read.unreadAt(NOW)).isFalse();
  }

  @Test
  @DisplayName("Should stay unread when it is a request whose offer still waits")
  void shouldStayUnreadWhenItIsARequestWhoseOfferStillWaits() {
    var request =
        new ShareNotification(
            Notification.builder().form(NotificationForm.REQUEST).build(),
            share(ProfileShareStatus.PENDING, NOW.plusSeconds(60)));

    assertThat(request.unreadAt(NOW)).isTrue();
    assertThat(request.resolutionAt(NOW)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should read as read and keep waiting when its recipient marks a waiting request read")
  void shouldReadAsReadAndKeepWaitingWhenItsRecipientMarksAWaitingRequestRead() {
    var request =
        new ShareNotification(
            Notification.builder().form(NotificationForm.REQUEST).readAt(NOW).build(),
            share(ProfileShareStatus.PENDING, NOW.plusSeconds(60)));

    assertThat(request.unreadAt(NOW)).isFalse();
    assertThat(request.resolutionAt(NOW)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("stoppedWaiting")
  @DisplayName("Should count as read with its resolution when its offer stopped waiting")
  void shouldCountAsReadWithItsResolutionWhenItsOfferStoppedWaiting(ProfileShareStatus status) {
    var request =
        new ShareNotification(
            Notification.builder().form(NotificationForm.REQUEST).build(),
            share(status, NOW.plusSeconds(60)));

    assertThat(request.unreadAt(NOW)).isFalse();
    assertThat(request.resolutionAt(NOW))
        .isEqualTo(RequestResolution.of(request.share(), NOW))
        .isPresent();
  }

  @Test
  @DisplayName(
      "Should count as read and expired when its offer's time ran out before the sweep stored it")
  void shouldCountAsReadAndExpiredWhenItsOffersTimeRanOutBeforeTheSweepStoredIt() {
    var request =
        new ShareNotification(
            Notification.builder().form(NotificationForm.REQUEST).build(),
            share(ProfileShareStatus.PENDING, NOW));

    assertThat(request.unreadAt(NOW)).isFalse();
    assertThat(request.resolutionAt(NOW)).contains(RequestResolution.EXPIRED);
  }

  static Stream<ProfileShareStatus> stoppedWaiting() {
    return Stream.of(ProfileShareStatus.values())
        .filter(status -> status != ProfileShareStatus.PENDING);
  }

  private static ProfileHouseholdShare share(ProfileShareStatus status, Instant expiresAt) {
    return ProfileHouseholdShare.builder().status(status).expiresAt(expiresAt).build();
  }
}
