package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.domain.Library;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Scheduler;

/** One broadcaster per library-administration subscription field. */
@Component
public class LibraryAdministrationBroadcasters {

  private final SubscriptionBroadcaster<Library> statusChanges;

  public LibraryAdministrationBroadcasters(
      MeterRegistry meterRegistry, Scheduler deliveryScheduler) {
    this.statusChanges =
        SubscriptionBroadcaster.<Library>builder()
            .field("libraryStatusChanged")
            .meterRegistry(meterRegistry)
            .deliveryScheduler(deliveryScheduler)
            .build();
  }

  public SubscriptionBroadcaster<Library> statusChanges() {
    return statusChanges;
  }
}
