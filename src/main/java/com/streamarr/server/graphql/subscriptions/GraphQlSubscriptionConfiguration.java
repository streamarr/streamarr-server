package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.graphql.dto.WatchStateChange;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
class GraphQlSubscriptionConfiguration {

  // A publishOn worker drains one operation at a time, so each operation still receives its
  // values in broadcast order on these virtual threads.
  @Bean(destroyMethod = "dispose")
  Scheduler graphQlSubscriptionDeliveryScheduler() {
    return Schedulers.fromExecutorService(
        Executors.newVirtualThreadPerTaskExecutor(), "graphql-subscription-delivery");
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
}
