package com.streamarr.server.services.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareAnswer;
import com.streamarr.server.domain.notification.ProfileShareEndedBy;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.ProfileShareSide;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.InMemoryNotificationStore;
import com.streamarr.server.services.notification.events.NotificationsWrittenEvent;
import com.streamarr.server.services.notification.events.WrittenNotification;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Notification Writer Tests")
class NotificationWriterTest {

  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
  private static final UUID SAM = UUID.randomUUID();
  private static final UUID KAI = UUID.randomUUID();
  private static final UUID SHARE = UUID.randomUUID();

  private final InMemoryNotificationStore<NotificationKind, ProfileShareFacts> store =
      new InMemoryNotificationStore<>(NotificationKind::getForm);
  private final CapturingEventPublisher events = new CapturingEventPublisher();
  private final NotificationWriter<NotificationKind, ProfileShareFacts> writer =
      new NotificationWriter<>(new ProfileShareKinds(), store, events);

  @Test
  @DisplayName("Should make one Notification for each recipient when an event is told")
  void shouldMakeOneNotificationForEachRecipientWhenAnEventIsTold() {
    writer.tell(offered().recipients(Set.of(SAM, KAI)).build());

    assertThat(store.rows())
        .extracting(InMemoryNotificationStore.Stored::recipientAccountId)
        .containsExactlyInAnyOrder(SAM, KAI);
  }

  @Test
  @DisplayName("Should tell nothing to the Account that acted when it is one of the recipients")
  void shouldTellNothingToTheAccountThatActedWhenItIsOneOfTheRecipients() {
    writer.tell(offered().recipients(Set.of(SAM, KAI)).actingAccountId(Optional.of(SAM)).build());

    assertThat(store.rows())
        .extracting(InMemoryNotificationStore.Stored::recipientAccountId)
        .containsExactly(KAI);
  }

  @Test
  @DisplayName("Should make a request for each event when the kind is a request")
  void shouldMakeARequestForEachEventWhenTheKindIsARequest() {
    writer.tell(offered().recipients(Set.of(SAM)).build());

    writer.tell(offered().recipients(Set.of(SAM)).at(NOW.plusSeconds(60)).build());

    assertThat(store.rowsOf(SAM)).hasSize(2);
  }

  @Test
  @DisplayName("Should update the unread news in place when its kind is told again about its share")
  void shouldUpdateTheUnreadNewsInPlaceWhenItsKindIsToldAgainAboutItsShare() {
    var made = writer.tell(answered("Ollie").build());

    var updated = writer.tell(answered("Ollie Rose").at(NOW.plusSeconds(60)).build());

    assertThat(updated)
        .containsExactly(
            new WrittenNotification(
                made.getFirst().notificationId(), SAM, WrittenNotification.Write.UPDATED));
    assertThat(store.rowsOf(SAM))
        .singleElement()
        .satisfies(
            stored -> {
              assertThat(stored.latestEventAt()).isEqualTo(NOW.plusSeconds(60));
              assertThat(stored.createdAt()).isEqualTo(NOW);
              assertThat(stored.facts())
                  .isEqualTo(ProfileShareFacts.answered("Ollie Rose", ProfileShareAnswer.ACCEPTED));
            });
  }

  @Test
  @DisplayName(
      "Should keep the side the news was addressed to when its kind is told again to another side")
  void shouldKeepTheSideTheNewsWasAddressedToWhenItsKindIsToldAgainToAnotherSide() {
    writer.tell(endedAs(ProfileShareSide.HOUSEHOLD_SIDE, "Ollie").build());

    writer.tell(
        endedAs(ProfileShareSide.OFFERING_SIDE, "Ollie Rose").at(NOW.plusSeconds(60)).build());

    assertThat(store.rowsOf(SAM))
        .singleElement()
        .extracting(InMemoryNotificationStore.Stored::facts)
        .isEqualTo(
            ProfileShareFacts.ended(
                "Ollie Rose", ProfileShareEndedBy.SERVER, ProfileShareSide.HOUSEHOLD_SIDE));
  }

  @Test
  @DisplayName("Should make new news when the recipient read the earlier news of the kind")
  void shouldMakeNewNewsWhenTheRecipientReadTheEarlierNewsOfTheKind() {
    var read = writer.tell(answered("Ollie").build()).getFirst();
    store.markRead(read.notificationId(), NOW);

    writer.tell(answered("Ollie").at(NOW.plusSeconds(60)).build());

    assertThat(store.rowsOf(SAM)).hasSize(2);
  }

  @Test
  @DisplayName("Should keep news of another kind apart when it is told about the same share")
  void shouldKeepNewsOfAnotherKindApartWhenItIsToldAboutTheSameShare() {
    writer.tell(answered("Ollie").build());

    writer.tell(
        event()
            .kind(NotificationKind.PROFILE_SHARE_ENDED)
            .facts(
                ProfileShareFacts.ended(
                    "Ollie", ProfileShareEndedBy.HOUSEHOLD_SIDE, ProfileShareSide.OFFERING_SIDE))
            .recipients(Set.of(SAM))
            .at(NOW.plusSeconds(60))
            .build());

    assertThat(store.rowsOf(SAM))
        .extracting(InMemoryNotificationStore.Stored::kind)
        .containsExactly(
            NotificationKind.PROFILE_SHARE_ANSWERED, NotificationKind.PROFILE_SHARE_ENDED);
  }

  @Test
  @DisplayName("Should announce each Notification made or updated when an event is told")
  void shouldAnnounceEachNotificationMadeOrUpdatedWhenAnEventIsTold() {
    var first = writer.tell(answered("Ollie").recipients(Set.of(SAM)).build()).getFirst();

    var second =
        writer
            .tell(answered("Ollie").recipients(Set.of(SAM, KAI)).at(NOW.plusSeconds(1)).build())
            .stream()
            .filter(written -> written.recipientAccountId().equals(KAI))
            .findFirst()
            .orElseThrow();

    var announced = events.getEventsOfType(NotificationsWrittenEvent.class);
    assertThat(announced).hasSize(2);
    assertThat(announced.getFirst().notifications()).containsExactly(first);
    assertThat(announced.getLast().notifications())
        .containsExactlyInAnyOrder(
            new WrittenNotification(first.notificationId(), SAM, WrittenNotification.Write.UPDATED),
            second);
    assertThat(first.write()).isEqualTo(WrittenNotification.Write.MADE);
    assertThat(second.write()).isEqualTo(WrittenNotification.Write.MADE);
  }

  @Test
  @DisplayName("Should announce nothing when nobody is told")
  void shouldAnnounceNothingWhenNobodyIsTold() {
    writer.tell(offered().recipients(Set.of(SAM)).actingAccountId(Optional.of(SAM)).build());

    assertThat(store.rows()).isEmpty();
    assertThat(events.getEventsOfType(NotificationsWrittenEvent.class)).isEmpty();
  }

  private static NotificationEvent.NotificationEventBuilder<NotificationKind, ProfileShareFacts>
      event() {
    return NotificationEvent.<NotificationKind, ProfileShareFacts>builder()
        .subjectId(SHARE)
        .actingAccountId(Optional.empty())
        .at(NOW);
  }

  private static NotificationEvent.NotificationEventBuilder<NotificationKind, ProfileShareFacts>
      offered() {
    return event()
        .kind(NotificationKind.PROFILE_SHARE_OFFERED)
        .facts(ProfileShareFacts.offered("Ollie"));
  }

  private static NotificationEvent.NotificationEventBuilder<NotificationKind, ProfileShareFacts>
      answered(String profileName) {
    return event()
        .kind(NotificationKind.PROFILE_SHARE_ANSWERED)
        .facts(ProfileShareFacts.answered(profileName, ProfileShareAnswer.ACCEPTED))
        .recipients(Set.of(SAM));
  }

  private static NotificationEvent.NotificationEventBuilder<NotificationKind, ProfileShareFacts>
      endedAs(ProfileShareSide side, String profileName) {
    return event()
        .kind(NotificationKind.PROFILE_SHARE_ENDED)
        .facts(ProfileShareFacts.ended(profileName, ProfileShareEndedBy.SERVER, side))
        .recipients(Set.of(SAM));
  }
}
