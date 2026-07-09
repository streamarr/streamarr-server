package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.fakes.FakeVersionCounterReader;
import com.streamarr.server.services.auth.CounterKind;
import com.streamarr.server.services.auth.TokenVersionCache;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Counter Notification Listener Tests")
class CounterNotificationListenerTest {

  @Test
  @DisplayName("Should retry after notification connection failure")
  void shouldRetryAfterNotificationConnectionFailure() {
    var cache = new TokenVersionCache(new FakeVersionCounterReader());
    var connectionSource = new ScriptedCounterNotificationConnectionSource();
    connectionSource.failNextOpen();
    var listener = new CounterNotificationListener(cache, connectionSource, _ -> {});

    listener.start();
    try {
      connectionSource.awaitOpenAttempts(2);
      connectionSource.awaitListenCount(1);
      assertThat(listener.isRunning()).isTrue();
      assertThat(listener.isListening()).isTrue();
    } finally {
      listener.stop();
    }

    await().atMost(Duration.ofSeconds(1)).until(() -> !listener.isListening());
  }

  @Test
  @DisplayName("Should clear stale cache entries whenever notification connection opens")
  void shouldClearStaleCacheEntriesWheneverNotificationConnectionOpens() {
    var sessionId = UUID.randomUUID();
    var reader = new FakeVersionCounterReader();
    reader.sessionVersions.put(sessionId, 1L);
    var cache = new TokenVersionCache(reader);
    var connectionSource = new ScriptedCounterNotificationConnectionSource();
    var listener = new CounterNotificationListener(cache, connectionSource, _ -> {});

    assertThat(cache.sessionVersion(sessionId)).contains(1L);
    reader.sessionVersions.put(sessionId, 2L);

    listener.start();
    try {
      connectionSource.awaitListenCount(1);
      assertThat(cache.sessionVersion(sessionId)).contains(2L);

      reader.sessionVersions.put(sessionId, 3L);
      assertThat(cache.sessionVersion(sessionId)).contains(2L);

      connectionSource.failActiveConnection();
      connectionSource.awaitListenCount(2);

      assertThat(cache.sessionVersion(sessionId)).contains(3L);
    } finally {
      listener.stop();
    }

    await().atMost(Duration.ofSeconds(1)).until(() -> !listener.isListening());
  }

  @Test
  @DisplayName("Should stop serving stale versions when notification connection is lost")
  void shouldStopServingStaleVersionsWhenNotificationConnectionLost() {
    var sessionId = UUID.randomUUID();
    var reader = new FakeVersionCounterReader();
    reader.sessionVersions.put(sessionId, 1L);
    var cache = new TokenVersionCache(reader);
    var connectionSource = new ScriptedCounterNotificationConnectionSource();
    var reconnectGate = new CountDownLatch(1);
    var listener =
        new CounterNotificationListener(
            cache, connectionSource, _ -> awaitReconnectGate(reconnectGate));

    listener.start();
    try {
      await().atMost(Duration.ofSeconds(1)).until(listener::isListening);
      assertThat(cache.sessionVersion(sessionId)).contains(1L);

      reader.sessionVersions.put(sessionId, 2L);
      assertThat(cache.sessionVersion(sessionId)).contains(1L);

      connectionSource.failActiveConnection();

      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(cache.sessionVersion(sessionId)).contains(2L));
    } finally {
      reconnectGate.countDown();
      listener.stop();
    }

    await().atMost(Duration.ofSeconds(1)).until(() -> !listener.isListening());
  }

  private static void awaitReconnectGate(CountDownLatch gate) {
    try {
      gate.await();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  @DisplayName("Should apply valid notification payloads to the cache")
  void shouldApplyValidNotificationPayloadsToTheCache() {
    var sessionId = UUID.randomUUID();
    var cache = new TokenVersionCache(new FakeVersionCounterReader());
    var connectionSource = new ScriptedCounterNotificationConnectionSource();
    var listener = new CounterNotificationListener(cache, connectionSource, _ -> {});

    listener.start();
    try {
      connectionSource.awaitListenCount(1);
      connectionSource.publish(
          new CounterNotificationPayload(CounterKind.SESSION, sessionId.toString(), 7L).encode());

      await()
          .atMost(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(cache.sessionVersion(sessionId)).contains(7L));
    } finally {
      listener.stop();
    }

    await().atMost(Duration.ofSeconds(1)).until(() -> !listener.isListening());
  }

  @Test
  @DisplayName("Should reconnect when applying a notification fails unexpectedly")
  void shouldReconnectWhenApplyingANotificationFailsUnexpectedly() {
    var sessionId = UUID.randomUUID();
    var poisonedSessionId = UUID.randomUUID();
    var cache =
        new TokenVersionCache(new FakeVersionCounterReader()) {
          @Override
          public void update(CounterKind kind, String key, long version) {
            if (key.equals(poisonedSessionId.toString())) {
              throw new IllegalStateException("poisoned update");
            }
            super.update(kind, key, version);
          }
        };
    var connectionSource = new ScriptedCounterNotificationConnectionSource();
    var listener = new CounterNotificationListener(cache, connectionSource, _ -> {});

    listener.start();
    try {
      connectionSource.awaitListenCount(1);
      connectionSource.publish(sessionPayload(poisonedSessionId, 5L));

      // An unexpected failure must not kill the worker; it reconnects and keeps applying.
      connectionSource.awaitListenCount(2);
      connectionSource.publish(sessionPayload(sessionId, 7L));

      await()
          .atMost(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(cache.sessionVersion(sessionId)).contains(7L));
    } finally {
      listener.stop();
    }

    await().atMost(Duration.ofSeconds(1)).until(() -> !listener.isListening());
  }

  private static String sessionPayload(UUID sessionId, long version) {
    return new CounterNotificationPayload(CounterKind.SESSION, sessionId.toString(), version)
        .encode();
  }

  @Test
  @DisplayName("Should stop listening when stopped while connected")
  void shouldStopListeningWhenStoppedWhileConnected() {
    var cache = new TokenVersionCache(new FakeVersionCounterReader());
    var connectionSource = new ScriptedCounterNotificationConnectionSource();
    var listener = new CounterNotificationListener(cache, connectionSource, _ -> {});

    listener.start();
    connectionSource.awaitListenCount(1);

    listener.stop();

    assertThat(listener.isRunning()).isFalse();
    await().atMost(Duration.ofSeconds(1)).until(() -> !listener.isListening());
    assertThat(connectionSource.openAttempts()).isEqualTo(1);
  }

  private static final class ScriptedCounterNotificationConnectionSource
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
