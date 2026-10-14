package com.streamarr.server.graphql.subscriptions;

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
}
