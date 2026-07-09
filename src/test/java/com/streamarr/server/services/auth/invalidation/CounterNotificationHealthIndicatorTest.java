package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.health.CounterNotificationHealthIndicator;
import com.streamarr.server.fakes.FakeVersionCounterReader;
import com.streamarr.server.services.auth.TokenVersionCache;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

@Tag("UnitTest")
@DisplayName("Counter Notification Health Indicator Tests")
class CounterNotificationHealthIndicatorTest {

  @Test
  @DisplayName("Should report up when the listener is listening")
  void shouldReportUpWhenListenerIsListening() {
    var cache = new TokenVersionCache(new FakeVersionCounterReader());
    var connectionSource = new ScriptedCounterNotificationConnectionSource();
    var listener = new CounterNotificationListener(cache, connectionSource, _ -> {});
    var indicator = new CounterNotificationHealthIndicator(listener);

    listener.start();
    try {
      await().atMost(Duration.ofSeconds(1)).until(listener::isListening);

      assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    } finally {
      listener.stop();
    }

    await().atMost(Duration.ofSeconds(1)).until(() -> !listener.isListening());
  }

  @Test
  @DisplayName("Should report down when the listener has not connected")
  void shouldReportDownWhenListenerHasNotConnected() {
    var cache = new TokenVersionCache(new FakeVersionCounterReader());
    var listener =
        new CounterNotificationListener(
            cache, new ScriptedCounterNotificationConnectionSource(), _ -> {});
    var indicator = new CounterNotificationHealthIndicator(listener);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("consecutiveFailures", 0);
  }

  @Test
  @DisplayName("Should report accumulating failures while the feed stays down")
  void shouldReportAccumulatingFailuresWhileFeedStaysDown() {
    var cache = new TokenVersionCache(new FakeVersionCounterReader());
    var connectionSource = new ScriptedCounterNotificationConnectionSource();
    for (var i = 0; i < 5; i++) {
      connectionSource.failNextOpen();
    }
    // Freeze the worker in the fifth backoff so the outage is observable mid-flight.
    var sleeps = new AtomicInteger();
    var reconnectGate = new CountDownLatch(1);
    var listener =
        new CounterNotificationListener(
            cache,
            connectionSource,
            _ -> {
              if (sleeps.incrementAndGet() == 5) {
                awaitReconnectGate(reconnectGate);
              }
            });
    var indicator = new CounterNotificationHealthIndicator(listener);

    listener.start();
    try {
      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(
              () -> {
                var health = indicator.health();
                assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                assertThat(health.getDetails()).containsEntry("consecutiveFailures", 5);
              });

      reconnectGate.countDown();
      await().atMost(Duration.ofSeconds(1)).until(listener::isListening);
      assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
      assertThat(listener.consecutiveConnectionFailures()).isZero();
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
}
