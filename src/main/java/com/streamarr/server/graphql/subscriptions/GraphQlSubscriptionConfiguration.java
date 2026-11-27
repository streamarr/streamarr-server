package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.graphql.dto.WatchStateChange;
import com.streamarr.server.properties.SubscriptionProperties;
import com.streamarr.server.services.auth.events.ProfileShareChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;

@Configuration(proxyBeanMethods = false)
class GraphQlSubscriptionConfiguration {

  @Bean
  SubscriptionDeliveryThreads subscriptionDeliveryThreads() {
    return new SubscriptionDeliveryThreads();
  }

  // A publishOn worker drains one operation at a time, so each operation still receives its
  // values in broadcast order on these virtual threads.
  @Bean
  Scheduler graphQlSubscriptionDeliveryScheduler(SubscriptionDeliveryThreads deliveryThreads) {
    return deliveryThreads.scheduler();
  }

  // Keyed by Profile id, so each change reaches only the operations of its Profile.
  @Bean
  KeyedSubscriptionBroadcaster<UUID, WatchStateChange> watchStateChangeBroadcaster(
      MeterRegistry meterRegistry,
      Scheduler graphQlSubscriptionDeliveryScheduler,
      SubscriptionProperties properties) {
    return KeyedSubscriptionBroadcaster.<UUID, WatchStateChange>builder()
        .field("watchStateChanged")
        .meterRegistry(meterRegistry)
        .deliveryScheduler(graphQlSubscriptionDeliveryScheduler)
        .operationBuffer(properties.operationBuffer())
        .build();
  }

  // Every operation receives every share change; each decides on delivery which it may read.
  @Bean
  SubscriptionBroadcaster<ProfileShareChangedEvent> profileShareChangeBroadcaster(
      MeterRegistry meterRegistry,
      Scheduler graphQlSubscriptionDeliveryScheduler,
      SubscriptionProperties properties) {
    return SubscriptionBroadcaster.<ProfileShareChangedEvent>builder()
        .field("profileShareChanged")
        .meterRegistry(meterRegistry)
        .deliveryScheduler(graphQlSubscriptionDeliveryScheduler)
        .operationBuffer(properties.operationBuffer())
        .build();
  }
}
