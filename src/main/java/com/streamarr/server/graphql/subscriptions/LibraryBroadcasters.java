package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.domain.Library;
import com.streamarr.server.graphql.dto.RemovedLibrary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Scheduler;

/** One broadcaster for each of libraryStatusChanged, libraryAdded and libraryRemoved. */
@Getter
@Component
public class LibraryBroadcasters {

  private final SubscriptionBroadcaster<Library> statusChanges;
  private final SubscriptionBroadcaster<Library> additions;
  private final SubscriptionBroadcaster<RemovedLibrary> removedLibraries;

  public LibraryBroadcasters(
      MeterRegistry meterRegistry, Scheduler deliveryScheduler, SubscriptionProperties properties) {
    var shared = new SharedSettings(meterRegistry, deliveryScheduler, properties.operationBuffer());
    this.statusChanges = shared.broadcaster("libraryStatusChanged");
    this.additions = shared.broadcaster("libraryAdded");
    this.removedLibraries = shared.broadcaster("libraryRemoved");
  }

  private record SharedSettings(
      MeterRegistry meterRegistry, Scheduler deliveryScheduler, int operationBuffer) {

    private <T> SubscriptionBroadcaster<T> broadcaster(String field) {
      return SubscriptionBroadcaster.<T>builder()
          .field(field)
          .meterRegistry(meterRegistry)
          .deliveryScheduler(deliveryScheduler)
          .operationBuffer(operationBuffer)
          .build();
    }
  }
}
