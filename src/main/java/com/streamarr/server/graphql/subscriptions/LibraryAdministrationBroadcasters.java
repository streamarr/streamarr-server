package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.domain.Library;
import com.streamarr.server.graphql.dto.LibraryRemoval;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Scheduler;

/** One broadcaster per library-administration subscription field. */
@Getter
@Component
public class LibraryAdministrationBroadcasters {

  private final SubscriptionBroadcaster<Library> statusChanges;
  private final SubscriptionBroadcaster<Library> additions;
  private final SubscriptionBroadcaster<LibraryRemoval> removals;

  public LibraryAdministrationBroadcasters(
      MeterRegistry meterRegistry, Scheduler deliveryScheduler, SubscriptionProperties properties) {
    var shared = new SharedSettings(meterRegistry, deliveryScheduler, properties.operationBuffer());
    this.statusChanges = shared.broadcaster("libraryStatusChanged");
    this.additions = shared.broadcaster("libraryAdded");
    this.removals = shared.broadcaster("libraryRemoved");
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
