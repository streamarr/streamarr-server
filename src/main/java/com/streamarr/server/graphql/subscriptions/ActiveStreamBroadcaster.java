package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.graphql.dto.ActiveStreamDto;
import com.streamarr.server.properties.SubscriptionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Scheduler;

/** The broadcaster of activeStreamsChanged. */
@Getter
@Component
public class ActiveStreamBroadcaster {

  private final SubscriptionBroadcaster<ActiveStreamDto.ChangeDto> changes;

  public ActiveStreamBroadcaster(
      MeterRegistry meterRegistry, Scheduler deliveryScheduler, SubscriptionProperties properties) {
    this.changes =
        SubscriptionBroadcaster.<ActiveStreamDto.ChangeDto>builder()
            .field("activeStreamsChanged")
            .meterRegistry(meterRegistry)
            .deliveryScheduler(deliveryScheduler)
            .operationBuffer(properties.operationBuffer())
            .build();
  }
}
