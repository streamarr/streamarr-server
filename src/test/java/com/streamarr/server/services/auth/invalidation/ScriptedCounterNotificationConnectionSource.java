package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Scripted stand-in for the JDBC connection source: tests enqueue payloads and failures. */
final class ScriptedCounterNotificationConnectionSource
    implements CounterNotificationConnectionSource {

  private final AtomicInteger openAttempts = new AtomicInteger();
  private final AtomicInteger listenCount = new AtomicInteger();
  private final ConcurrentLinkedQueue<CounterNotificationConnectionException> openFailures =
      new ConcurrentLinkedQueue<>();
  private final BlockingQueue<PollResult> pollResults = new LinkedBlockingQueue<>();

  void failNextOpen() {
    openFailures.add(new StacklessConnectionException("connection failed"));
  }

  void failActiveConnection() {
    pollResults.add(new Failure(new StacklessConnectionException("connection lost")));
  }

  void publish(String payload) {
    pollResults.add(new Notifications(List.of(payload)));
  }

  void awaitOpenAttempts(int expected) {
    await()
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(openAttempts()).isEqualTo(expected));
  }

  void awaitListenCount(int expected) {
    await()
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(listenCount.get()).isEqualTo(expected));
  }

  int openAttempts() {
    return openAttempts.get();
  }

  @Override
  public CounterNotificationConnection open() {
    openAttempts.incrementAndGet();
    var failure = openFailures.poll();
    if (failure != null) {
      throw failure;
    }
    return new ScriptedCounterNotificationConnection();
  }

  private final class ScriptedCounterNotificationConnection
      implements CounterNotificationConnection {

    @Override
    public void listen() {
      listenCount.incrementAndGet();
    }

    @Override
    public List<String> notifications(int pollTimeoutMs) {
      try {
        return switch (pollResults.take()) {
          case Notifications(var payloads) -> payloads;
          case Failure(var exception) -> throw exception;
        };
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CounterNotificationConnectionException("interrupted", e);
      }
    }

    @Override
    public void close() {
      // The scripted fake has no external resource to release.
    }
  }

  private sealed interface PollResult permits Notifications, Failure {}

  private record Notifications(List<String> payloads) implements PollResult {}

  private record Failure(CounterNotificationConnectionException exception) implements PollResult {}

  private static final class StacklessConnectionException
      extends CounterNotificationConnectionException {

    private StacklessConnectionException(String reason) {
      super(reason, null);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }
}
