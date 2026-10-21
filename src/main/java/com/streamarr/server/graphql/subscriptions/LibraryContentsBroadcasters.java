package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.services.events.library.LibraryItemAddedEvent;
import com.streamarr.server.services.events.library.LibraryItemRemovedEvent;
import com.streamarr.server.services.events.library.LibraryItemUpdatedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.Getter;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Scheduler;

@Getter
@Component
public class LibraryContentsBroadcasters {

  private final KeyedSubscriptionBroadcaster<UUID, LibraryItemAddedEvent> additions;
  private final KeyedSubscriptionBroadcaster<UUID, LibraryItemUpdatedEvent> updates;
  private final KeyedSubscriptionBroadcaster<UUID, LibraryItemRemovedEvent> removals;
  private final KeyedSubscriptionBroadcaster<UUID, UUID> counts;

  public LibraryContentsBroadcasters(
      MeterRegistry registry, Scheduler scheduler, SubscriptionProperties properties) {
    additions = broadcaster("libraryItemAdded", registry, scheduler, properties);
    updates = broadcaster("libraryItemUpdated", registry, scheduler, properties);
    removals = broadcaster("libraryItemRemoved", registry, scheduler, properties);
    counts = broadcaster("libraryCountsChanged", registry, scheduler, properties);
  }

  private static <T> KeyedSubscriptionBroadcaster<UUID, T> broadcaster(
      String field,
      MeterRegistry registry,
      Scheduler scheduler,
      SubscriptionProperties properties) {
    return KeyedSubscriptionBroadcaster.<UUID, T>builder()
        .field(field)
        .meterRegistry(registry)
        .deliveryScheduler(scheduler)
        .operationBuffer(properties.operationBuffer())
        .build();
  }
}
