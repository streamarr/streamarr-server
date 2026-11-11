package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.services.auth.events.ProfileShareChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

@Tag("UnitTest")
@DisplayName("Profile Share Event Listener Tests")
class ProfileShareEventListenerTest {

  private final SubscriptionBroadcaster<ProfileShareChangedEvent> broadcaster =
      SubscriptionBroadcaster.<ProfileShareChangedEvent>builder()
          .field("profileShareChanged")
          .meterRegistry(new SimpleMeterRegistry())
          .deliveryScheduler(Schedulers.immediate())
          .operationBuffer(4)
          .build();
  private final ProfileShareEventListener listener = new ProfileShareEventListener(broadcaster);

  @Test
  @DisplayName("Should pass each share change to every operation when a share change commits")
  void shouldPassEachShareChangeToEveryOperationWhenAShareChangeCommits() {
    var change =
        new ProfileShareChangedEvent(
            new ChangedShare(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ProfileShareStatus.PENDING),
            true);
    var first = subscribe();
    var second = subscribe();

    listener.onProfileShareChanged(change);

    assertThat(first).containsExactly(change);
    assertThat(second).containsExactly(change);
  }

  private List<ProfileShareChangedEvent> subscribe() {
    var received = new CopyOnWriteArrayList<ProfileShareChangedEvent>();
    broadcaster.register().subscribe(received::add);
    return received;
  }
}
