package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.domain.Library;
import com.streamarr.server.graphql.dto.LibraryRemoval;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Scheduler;

/** One broadcaster per library-administration subscription field. */
@Component
public class LibraryAdministrationBroadcasters {

  private final SubscriptionBroadcaster<Library> statusChanges;
  private final SubscriptionBroadcaster<Library> additions;
  private final SubscriptionBroadcaster<LibraryRemoval> removals;

  public LibraryAdministrationBroadcasters(
      MeterRegistry meterRegistry, Scheduler deliveryScheduler, SubscriptionProperties properties) {
    this.statusChanges =
        SubscriptionBroadcaster.<Library>builder()
            .field("libraryStatusChanged")
            .meterRegistry(meterRegistry)
            .deliveryScheduler(deliveryScheduler)
            .operationBuffer(properties.operationBuffer())
            .build();
    this.additions =
        SubscriptionBroadcaster.<Library>builder()
            .field("libraryAdded")
            .meterRegistry(meterRegistry)
            .deliveryScheduler(deliveryScheduler)
            .operationBuffer(properties.operationBuffer())
            .build();
    this.removals =
        SubscriptionBroadcaster.<LibraryRemoval>builder()
            .field("libraryRemoved")
            .meterRegistry(meterRegistry)
            .deliveryScheduler(deliveryScheduler)
            .operationBuffer(properties.operationBuffer())
            .build();
  }

  public SubscriptionBroadcaster<Library> statusChanges() {
    return statusChanges;
  }

  public SubscriptionBroadcaster<Library> additions() {
    return additions;
  }

  public SubscriptionBroadcaster<LibraryRemoval> removals() {
    return removals;
  }
}
