package com.streamarr.server.services.auth.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.CounterKind;
import com.streamarr.server.domain.auth.MembershipVersionChange;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.repositories.auth.CounterNotificationPayload;
import com.streamarr.server.repositories.auth.CounterNotificationWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Counter Change Event Publisher")
class CounterChangeEventPublisherTest {

  @Test
  @DisplayName("Should publish local event and remote notification once when session changes")
  void shouldPublishLocalEventAndRemoteNotificationOnceWhenSessionChanges() {
    var eventPublisher = new CapturingEventPublisher();
    var notificationWriter = new RecordingCounterNotificationWriter();
    var publisher = new CounterChangeEventPublisher(eventPublisher, notificationWriter);
    var sessionId = UUID.randomUUID();

    publisher.publishSession(sessionId, 3L);

    assertThat(eventPublisher.getEventsOfType(CounterBumpedEvent.class))
        .containsExactly(CounterBumpedEvent.session(sessionId, 3L));
    assertThat(notificationWriter.payloads)
        .containsExactly(
            new CounterNotificationPayload(CounterKind.SESSION, sessionId.toString(), 3L));
  }

  @Test
  @DisplayName("Should publish local event and remote notification once when membership changes")
  void shouldPublishLocalEventAndRemoteNotificationOnceWhenMembershipChanges() {
    var eventPublisher = new CapturingEventPublisher();
    var notificationWriter = new RecordingCounterNotificationWriter();
    var publisher = new CounterChangeEventPublisher(eventPublisher, notificationWriter);
    var change = new MembershipVersionChange(UUID.randomUUID(), UUID.randomUUID(), 7L);

    publisher.publishMembership(change);

    assertThat(eventPublisher.getEventsOfType(CounterBumpedEvent.class))
        .containsExactly(
            CounterBumpedEvent.membership(
                change.accountId(), change.householdId(), change.version()));
    assertThat(notificationWriter.payloads)
        .containsExactly(
            new CounterNotificationPayload(
                CounterKind.MEMBERSHIP,
                CounterBumpedEvent.membershipKey(change.accountId(), change.householdId()),
                change.version()));
  }

  private static final class RecordingCounterNotificationWriter
      implements CounterNotificationWriter {

    private final List<CounterNotificationPayload> payloads = new ArrayList<>();

    @Override
    public void write(CounterNotificationPayload payload) {
      payloads.add(payload);
    }
  }
}
