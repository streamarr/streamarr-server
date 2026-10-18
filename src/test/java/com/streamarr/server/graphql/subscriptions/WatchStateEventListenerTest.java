package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.graphql.dto.WatchStateChange;
import com.streamarr.server.services.events.watchprogress.WatchStateChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

@Tag("UnitTest")
@DisplayName("Watch State Event Listener Tests")
class WatchStateEventListenerTest {

  private final KeyedSubscriptionBroadcaster<UUID, WatchStateChange> broadcaster =
      KeyedSubscriptionBroadcaster.<UUID, WatchStateChange>builder()
          .field("watchStateChanged")
          .meterRegistry(new SimpleMeterRegistry())
          .deliveryScheduler(Schedulers.immediate())
          .operationBuffer(4)
          .build();
  private final WatchStateEventListener listener = new WatchStateEventListener(broadcaster);

  @Test
  @DisplayName("Should pass the change's items only to the operations of the change's Profile")
  void shouldPassChangesItemsOnlyToOperationsOfChangesProfile() {
    var profileId = UUID.randomUUID();
    var otherProfileId = UUID.randomUUID();
    var itemIds = Set.of(UUID.randomUUID(), UUID.randomUUID());
    var received = subscribe(profileId);
    var otherReceived = subscribe(otherProfileId);

    listener.onWatchStateChanged(new WatchStateChangedEvent(profileId, itemIds));

    assertThat(received).containsExactly(new WatchStateChange(itemIds));
    assertThat(otherReceived).isEmpty();
  }

  private List<WatchStateChange> subscribe(UUID profileId) {
    var received = new CopyOnWriteArrayList<WatchStateChange>();
    broadcaster.register(profileId).subscribe(received::add);
    return received;
  }
}
