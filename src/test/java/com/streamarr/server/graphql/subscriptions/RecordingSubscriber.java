package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;

/** Requests a fixed demand up front and keeps every signal for the test to take. */
final class RecordingSubscriber extends BaseSubscriber<String> {

  private static final Duration BOUND = Duration.ofSeconds(5);

  private final long initialDemand;
  private final BlockingQueue<String> values = new LinkedBlockingQueue<>();
  private final Queue<String> deliveryThreads = new ConcurrentLinkedQueue<>();
  private final CompletableFuture<Throwable> error = new CompletableFuture<>();

  RecordingSubscriber(long initialDemand) {
    this.initialDemand = initialDemand;
  }

  @Override
  protected void hookOnSubscribe(Subscription subscription) {
    if (initialDemand > 0) {
      request(initialDemand);
    }
  }

  @Override
  protected void hookOnNext(String value) {
    deliveryThreads.add(Thread.currentThread().getName());
    values.add(value);
  }

  @Override
  protected void hookOnError(Throwable throwable) {
    error.complete(throwable);
  }

  Throwable awaitError() {
    try {
      return error.get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for an error", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new AssertionError("No error within " + BOUND, e);
    }
  }

  List<String> deliveryThreads() {
    return List.copyOf(deliveryThreads);
  }

  String take() {
    try {
      var value = values.poll(BOUND.toMillis(), TimeUnit.MILLISECONDS);
      assertThat(value).as("a delivered value within %s", BOUND).isNotNull();
      return value;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for a delivery", e);
    }
  }
}
