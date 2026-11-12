package com.streamarr.server.services.events.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.notification.ProfileShareAnswer;
import com.streamarr.server.domain.notification.ProfileShareEndedBy;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Tell Notification Event Tests")
class TellNotificationEventTest {

  private static final Instant EVENT = Instant.parse("2026-11-12T10:00:00Z");

  private final SubjectReference share = SubjectReference.profileShare(UUID.randomUUID());

  @Test
  @DisplayName("Should refuse a tell when its snapshot is not of its kind's type")
  void shouldRefuseATellWhenItsSnapshotIsNotOfItsKindsType() {
    var tell =
        answered()
            .snapshot(new ProfileShareEndedSnapshot("Kai", ProfileShareEndedBy.HOUSEHOLD_SIDE));

    assertThatThrownBy(tell::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should refuse a tell when its subject is not of its kind's subject type")
  void shouldRefuseATellWhenItsSubjectIsNotOfItsKindsSubjectType() {
    var tell = answered().subject(new SubjectReference("HOUSEHOLD", UUID.randomUUID()));

    assertThatThrownBy(tell::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should refuse a tell of news when it carries an expiry")
  void shouldRefuseATellOfNewsWhenItCarriesAnExpiry() {
    var tell = answered().subjectExpiresAt(Optional.of(EVENT.plusSeconds(60)));

    assertThatThrownBy(tell::build).isInstanceOf(IllegalArgumentException.class);
  }

  private TellNotificationEvent.TellNotificationEventBuilder answered() {
    return TellNotificationEvent.builder()
        .kind(SharingNotificationKinds.PROFILE_SHARE_ANSWERED)
        .subject(share)
        .recipients(Set.of(RecipientReference.account(UUID.randomUUID())))
        .snapshot(new ProfileShareAnsweredSnapshot("Kai", ProfileShareAnswer.ACCEPTED))
        .at(EVENT);
  }
}
