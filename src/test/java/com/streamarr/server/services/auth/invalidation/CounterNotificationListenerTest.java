package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.auth.CounterKind;
import com.streamarr.server.fakes.FakeVersionCounterReader;
import com.streamarr.server.repositories.auth.CounterNotificationPayload;
import com.streamarr.server.services.auth.TokenVersionCache;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
      // The fake counts listen() before the listener flips its flag; await the observable state.
      await().atMost(Duration.ofSeconds(1)).until(listener::isListening);
      assertThat(listener.isRunning()).isTrue();
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
      await().atMost(Duration.ofSeconds(1)).until(listener::isListening);
      assertThat(cache.sessionVersion(sessionId)).contains(2L);

      reader.sessionVersions.put(sessionId, 3L);
      assertThat(cache.sessionVersion(sessionId)).contains(2L);

      connectionSource.failActiveConnection();
      connectionSource.awaitListenCount(2);

      await()
          .atMost(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(cache.sessionVersion(sessionId)).contains(3L));
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

  @Test
  @DisplayName("Should keep reading through while notification connection is unavailable")
  void shouldKeepReadingThroughWhileNotificationConnectionIsUnavailable() {
    var sessionId = UUID.randomUUID();
    var reader = new FakeVersionCounterReader();
    reader.sessionVersions.put(sessionId, 1L);
    var cache = new TokenVersionCache(reader);
    var connectionSource = new ScriptedCounterNotificationConnectionSource();
    var backoffEntered = new CountDownLatch(1);
    var reconnectGate = new CountDownLatch(1);
    var listener =
        new CounterNotificationListener(
            cache,
            connectionSource,
            _ -> {
              backoffEntered.countDown();
              awaitReconnectGate(reconnectGate);
            });

    listener.start();
    try {
      await().atMost(Duration.ofSeconds(1)).until(listener::isListening);
      assertThat(cache.sessionVersion(sessionId)).contains(1L);

      connectionSource.failActiveConnection();
      awaitReconnectGate(backoffEntered);

      reader.sessionVersions.put(sessionId, 2L);
      assertThat(cache.sessionVersion(sessionId)).contains(2L);

      // This remote bump is missed while disconnected. The next lookup must still reach the
      // authoritative reader instead of using the value returned by the previous lookup.
      reader.sessionVersions.put(sessionId, 3L);
      assertThat(cache.sessionVersion(sessionId)).contains(3L);
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
  @DisplayName("Should apply valid notification payloads to a warm cache")
  void shouldApplyValidNotificationPayloadsToWarmCache() {
    var sessionId = UUID.randomUUID();
    var reader = new FakeVersionCounterReader();
    reader.sessionVersions.put(sessionId, 6L);
    var cache = new TokenVersionCache(reader);
    var connectionSource = new ScriptedCounterNotificationConnectionSource();
    var listener = new CounterNotificationListener(cache, connectionSource, _ -> {});

    listener.start();
    try {
      connectionSource.awaitListenCount(1);
      await().atMost(Duration.ofSeconds(1)).until(listener::isListening);
      assertThat(cache.sessionVersion(sessionId)).contains(6L);
      reader.sessionVersions.put(sessionId, 7L);
      reader.failWith(new IllegalStateException("notification should advance the warm entry"));
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
  @DisplayName("Should clear the cache when a malformed notification arrives")
  void shouldClearCacheWhenMalformedNotificationArrives() {
    var sessionId = UUID.randomUUID();
    var fenceId = UUID.randomUUID();
    var reader = new FakeVersionCounterReader();
    reader.sessionVersions.put(sessionId, 5L);
    reader.sessionVersions.put(fenceId, 1L);
    var cache = new TokenVersionCache(reader);
    var connectionSource = new ScriptedCounterNotificationConnectionSource();
    var listener = new CounterNotificationListener(cache, connectionSource, _ -> {});

    listener.start();
    try {
      connectionSource.awaitListenCount(1);
      await().atMost(Duration.ofSeconds(1)).until(listener::isListening);
      assertThat(cache.sessionVersion(sessionId)).contains(5L);
      assertThat(cache.sessionVersion(fenceId)).contains(1L);

      // Simulate a missed bump carried by a forward-incompatible payload.
      reader.sessionVersions.put(sessionId, 9L);
      connectionSource.publish("SESSION|" + sessionId + "|9|EXTRA");

      // The well-formed fence proves the malformed payload was processed first.
      reader.sessionVersions.put(fenceId, 2L);
      connectionSource.publish(
          new CounterNotificationPayload(CounterKind.SESSION, fenceId.toString(), 2L).encode());
      await()
          .atMost(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(cache.sessionVersion(fenceId)).contains(2L));

      assertThat(cache.sessionVersion(sessionId)).contains(9L);
    } finally {
      listener.stop();
    }

    await().atMost(Duration.ofSeconds(1)).until(() -> !listener.isListening());
  }

  @Test
  @DisplayName("Should back off exponentially to the cap and reset after reconnecting")
  void shouldBackOffExponentiallyToTheCapAndResetAfterReconnecting() {
    var recordedSleeps = new CopyOnWriteArrayList<Long>();
    var cache = new TokenVersionCache(new FakeVersionCounterReader());
    var connectionSource = new ScriptedCounterNotificationConnectionSource();
    for (var i = 0; i < 6; i++) {
      connectionSource.failNextOpen();
    }
    var listener = new CounterNotificationListener(cache, connectionSource, recordedSleeps::add);

    listener.start();
    try {
      // Six failed opens precede the successful connect, so every sleep is already recorded.
      connectionSource.awaitListenCount(1);
      assertThat(recordedSleeps).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L);

      // A reconnect after a healthy connection starts the schedule over.
      connectionSource.failActiveConnection();
      connectionSource.awaitListenCount(2);
      assertThat(recordedSleeps)
          .containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 1_000L);
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
    var reader = new FakeVersionCounterReader();
    reader.sessionVersions.put(sessionId, 7L);
    var cache =
        new TokenVersionCache(reader) {
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
      await().atMost(Duration.ofSeconds(1)).until(listener::isListening);
      assertThat(cache.sessionVersion(sessionId)).contains(7L);
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

  @Test
  @DisplayName("Should keep caching suspended when stopped during listen")
  void shouldKeepCachingSuspendedWhenStoppedDuringListen() {
    var sessionId = UUID.randomUUID();
    var reader = new FakeVersionCounterReader();
    reader.sessionVersions.put(sessionId, 1L);
    var cache = new TokenVersionCache(reader);
    var connectionSource = new PausingListenConnectionSource();
    var listener = new CounterNotificationListener(cache, connectionSource, _ -> {});

    listener.start();
    connectionSource.awaitListen();
    listener.stop();
    connectionSource.releaseListen();
    connectionSource.awaitCloseStarted();

    try {
      reader.sessionVersions.put(sessionId, 2L);
      assertThat(cache.sessionVersion(sessionId)).contains(2L);
      reader.sessionVersions.put(sessionId, 3L);
      assertThat(cache.sessionVersion(sessionId)).contains(3L);
    } finally {
      connectionSource.releaseClose();
    }

    await().atMost(Duration.ofSeconds(1)).until(() -> !listener.isListening());
  }

  private static final class PausingListenConnectionSource
      implements CounterNotificationConnectionSource {

    private final CountDownLatch listenEntered = new CountDownLatch(1);
    private final CountDownLatch releaseListen = new CountDownLatch(1);
    private final CountDownLatch closeStarted = new CountDownLatch(1);
    private final CountDownLatch releaseClose = new CountDownLatch(1);

    @Override
    public CounterNotificationConnection open() {
      return new CounterNotificationConnection() {
        @Override
        public void listen() {
          listenEntered.countDown();
          awaitIgnoringInterrupts(releaseListen);
        }

        @Override
        public List<String> notifications(int pollTimeoutMs) {
          return List.of();
        }

        @Override
        public void close() {
          closeStarted.countDown();
          awaitIgnoringInterrupts(releaseClose);
        }
      };
    }

    private void awaitListen() {
      awaitReconnectGate(listenEntered);
    }

    private void releaseListen() {
      releaseListen.countDown();
    }

    private void awaitCloseStarted() {
      awaitReconnectGate(closeStarted);
    }

    private void releaseClose() {
      releaseClose.countDown();
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
      while (latch.getCount() > 0) {
        try {
          latch.await();
        } catch (InterruptedException _) {
          // A real JDBC LISTEN can finish normally after stop races it; preserve that boundary.
        }
      }
    }
  }
}
