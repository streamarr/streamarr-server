package com.streamarr.server.graphql.subscriptions;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The virtual threads that deliver every subscription operation's values. The scheduler supports
 * {@code publishOn} only: it rejects delayed and periodic tasks.
 */
final class SubscriptionDeliveryThreads implements AutoCloseable {

  // The deliveries close with the server, after its shutdown phase, within its stop deadline.
  private static final Duration CLOSE_BOUND = Duration.ofSeconds(1);

  private final ExecutorService threads =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("graphql-subscription-delivery-", 0).factory());

  // Reactor sees only a plain Executor, not the ExecutorService: it cancels an ExecutorService
  // task with an interrupt, and an interrupt closes the database connection that a running
  // delivery reads through when its operation ends.
  private final Scheduler scheduler = Schedulers.fromExecutor(threads::execute);

  Scheduler scheduler() {
    return scheduler;
  }

  /**
   * Rejects new deliveries, then waits for running ones to finish without interrupting them. A
   * delivery still running after the bound, or when the closing thread is interrupted, is
   * interrupted.
   */
  @Override
  public void close() {
    scheduler.dispose();
    threads.shutdown();
    try {
      if (!threads.awaitTermination(CLOSE_BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
        threads.shutdownNow();
      }
    } catch (InterruptedException _) {
      threads.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
