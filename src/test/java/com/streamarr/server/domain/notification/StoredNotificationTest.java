package com.streamarr.server.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.events.notification.ProfileShareAnswer;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareOfferedSnapshot;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Stored Notification Tests")
class StoredNotificationTest {

  private static final Instant MADE = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant EXPIRY = MADE.plus(Duration.ofDays(7));

  @Test
  @DisplayName("Should read as expired from its expiry when no event resolved it before then")
  void shouldReadAsExpiredFromItsExpiryWhenNoEventResolvedItBeforeThen() {
    var request = waitingRequest().build();

    assertThat(request.resolutionAt(EXPIRY.minusMillis(1))).isEmpty();
    assertThat(request.resolutionTimeAt(EXPIRY.minusMillis(1))).isEmpty();
    assertThat(request.resolutionAt(EXPIRY)).contains(RequestResolution.EXPIRED);
    assertThat(request.resolutionTimeAt(EXPIRY.plusSeconds(60))).contains(EXPIRY);
    assertThat(request.lineAt(EXPIRY)).isEqualTo("The offer of “Kai” to your Household expired.");
  }

  @Test
  @DisplayName("Should read as the event resolved it when an event resolved it before its expiry")
  void shouldReadAsTheEventResolvedItWhenAnEventResolvedItBeforeItsExpiry() {
    var decidedAt = MADE.plus(Duration.ofDays(1));
    var request =
        waitingRequest()
            .snapshot(
                new ProfileShareOfferedSnapshot("Kai", Optional.of(ProfileShareAnswer.ACCEPTED)))
            .resolution(Optional.of(RequestResolution.DECIDED))
            .resolvedAt(Optional.of(decidedAt))
            .build();

    assertThat(request.resolutionAt(EXPIRY.plusSeconds(60))).contains(RequestResolution.DECIDED);
    assertThat(request.resolutionTimeAt(EXPIRY.plusSeconds(60))).contains(decidedAt);
  }

  @Test
  @DisplayName("Should count as read once it resolves when its recipient never marked it")
  void shouldCountAsReadOnceItResolvesWhenItsRecipientNeverMarkedIt() {
    var request = waitingRequest().build();

    assertThat(request.unreadAt(MADE)).isTrue();
    assertThat(request.unreadAt(EXPIRY)).isFalse();
    assertThat(
            waitingRequest()
                .resolution(Optional.of(RequestResolution.WITHDRAWN))
                .resolvedAt(Optional.of(MADE))
                .build()
                .unreadAt(MADE))
        .isFalse();
  }

  @Test
  @DisplayName("Should stay unread until its recipient marks it when it is news")
  void shouldStayUnreadUntilItsRecipientMarksItWhenItIsNews() {
    var news =
        waitingRequest()
            .kind(SharingNotificationKinds.PROFILE_SHARE_ANSWERED)
            .snapshot(new ProfileShareAnsweredSnapshot("Kai", ProfileShareAnswer.ACCEPTED))
            .expiresAt(Optional.empty());

    var later = MADE.plus(Duration.ofDays(400));
    assertThat(news.build().unreadAt(later)).isTrue();
    assertThat(news.build().resolutionAt(later)).isEmpty();
    assertThat(news.readAt(Optional.of(MADE)).build().unreadAt(later)).isFalse();
  }

  private static StoredNotification.StoredNotificationBuilder waitingRequest() {
    return StoredNotification.builder()
        .id(UUID.randomUUID())
        .recipient(RecipientReference.account(UUID.randomUUID()))
        .kind(SharingNotificationKinds.PROFILE_SHARE_OFFERED)
        .subject(SubjectReference.profileShare(UUID.randomUUID()))
        .snapshot(new ProfileShareOfferedSnapshot("Kai", Optional.empty()))
        .createdAt(MADE)
        .latestEventAt(MADE)
        .readAt(Optional.empty())
        .resolution(Optional.empty())
        .resolvedAt(Optional.empty())
        .expiresAt(Optional.of(EXPIRY));
  }
}
