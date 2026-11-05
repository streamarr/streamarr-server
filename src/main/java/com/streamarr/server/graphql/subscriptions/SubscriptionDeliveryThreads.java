package com.streamarr.server.graphql.subscriptions;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** The virtual threads that deliver every subscription operation's values. */
final class SubscriptionDeliveryThreads implements AutoCloseable {

  // A plain Executor, not an ExecutorService: Reactor cancels an ExecutorService task with an
  // interrupt, and an interrupt closes the database connection that a running delivery reads
  // through when its operation ends.
  private final Scheduler scheduler;

  SubscriptionDeliveryThreads() {
    var deliveryThreads = Thread.ofVirtual().name("graphql-subscription-delivery-", 0).factory();
    this.scheduler = Schedulers.fromExecutor(task -> deliveryThreads.newThread(task).start());
  }

  Scheduler scheduler() {
    return scheduler;
  }

  @Override
  public void close() {
    scheduler.dispose();
  }
}
