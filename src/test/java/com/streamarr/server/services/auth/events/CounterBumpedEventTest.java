package com.streamarr.server.services.auth.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.auth.CounterKind;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Counter bumped event tests")
class CounterBumpedEventTest {

  @Test
  @DisplayName("Should expose only typed event factories")
  void shouldExposeOnlyTypedEventFactories() {
    var sessionId = UUID.randomUUID();
    var accountId = UUID.randomUUID();
    var householdId = UUID.randomUUID();
    var profileId = UUID.randomUUID();

    assertThat(CounterBumpedEvent.class.getConstructors()).isEmpty();
    assertThat(CounterBumpedEvent.session(sessionId, 1))
        .extracting(CounterBumpedEvent::kind, CounterBumpedEvent::key, CounterBumpedEvent::version)
        .containsExactly(CounterKind.SESSION, sessionId.toString(), 1L);
    assertThat(CounterBumpedEvent.membership(accountId, householdId, 2))
        .extracting(CounterBumpedEvent::kind, CounterBumpedEvent::key, CounterBumpedEvent::version)
        .containsExactly(CounterKind.MEMBERSHIP, accountId + ":" + householdId, 2L);
    assertThat(CounterBumpedEvent.profile(profileId, 3))
        .extracting(CounterBumpedEvent::kind, CounterBumpedEvent::key, CounterBumpedEvent::version)
        .containsExactly(CounterKind.PROFILE, profileId.toString(), 3L);
  }
}
