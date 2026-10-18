package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.services.events.library.LibraryItemChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.Getter;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Scheduler;

@Getter
@Component
public class LibraryContentsBroadcasters {

  private final KeyedSubscriptionBroadcaster<UUID, LibraryItemChangedEvent> additions;
  private final KeyedSubscriptionBroadcaster<UUID, LibraryItemChangedEvent> updates;
  private final KeyedSubscriptionBroadcaster<UUID, LibraryItemChangedEvent> removals;
  private final KeyedSubscriptionBroadcaster<UUID, UUID> counts;

  public LibraryContentsBroadcasters(
      MeterRegistry registry, Scheduler scheduler, SubscriptionProperties properties) {
    var items =
        KeyedSubscriptionBroadcaster.<UUID, LibraryItemChangedEvent>builder()
            .meterRegistry(registry)
            .deliveryScheduler(scheduler)
            .operationBuffer(properties.operationBuffer());
    additions = items.field("libraryItemAdded").build();
    updates = items.field("libraryItemUpdated").build();
    removals = items.field("libraryItemRemoved").build();
    counts =
        KeyedSubscriptionBroadcaster.<UUID, UUID>builder()
            .field("libraryCountsChanged")
            .meterRegistry(registry)
            .deliveryScheduler(scheduler)
            .operationBuffer(properties.operationBuffer())
            .build();
  }
}
