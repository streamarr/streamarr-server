package com.streamarr.server.graphql.subscriptions;

import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
class GraphQlSubscriptionConfiguration {

  /**
   * Runs subscription delivery on virtual threads. A publishOn worker drains one operation at a
   * time, so each operation still receives its values in broadcast order.
   */
  @Bean(destroyMethod = "dispose")
  Scheduler graphQlSubscriptionDeliveryScheduler() {
    return Schedulers.fromExecutorService(
        Executors.newVirtualThreadPerTaskExecutor(), "graphql-subscription-delivery");
  }
}
