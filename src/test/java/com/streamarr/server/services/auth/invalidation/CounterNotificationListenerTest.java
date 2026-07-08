package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.fakes.FakeVersionCounterReader;
import com.streamarr.server.services.auth.TokenVersionCache;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

@Tag("UnitTest")
@DisplayName("Counter Notification Listener Tests")
class CounterNotificationListenerTest {

  @Test
  @DisplayName("Should keep retrying when database unreachable")
  void shouldKeepRetryingWhenDatabaseUnreachable() {
    var cache = new TokenVersionCache(new FakeVersionCounterReader());
    var listener = new CounterNotificationListener(cache, unreachableConnectionDetails());

    listener.start();
    try {
      // Outlasting a full backoff cycle proves the reconnect loop survives repeated connection
      // failures: alive, backing off, never listening.
      await()
          .during(Duration.ofSeconds(2))
          .atMost(Duration.ofSeconds(5))
          .until(() -> listener.isRunning() && !listener.isListening());
    } finally {
      listener.stop();
    }

    await().atMost(Duration.ofSeconds(5)).until(() -> !listener.isRunning());
  }

  @Test
  @DisplayName("Should stay stopped when stopped before start")
  void shouldStayStoppedWhenStoppedBeforeStart() {
    var cache = new TokenVersionCache(new FakeVersionCounterReader());
    var listener = new CounterNotificationListener(cache, unreachableConnectionDetails());

    listener.stop();

    assertThat(listener.isRunning()).isFalse();
    assertThat(listener.isListening()).isFalse();
  }

  private static JdbcConnectionDetails unreachableConnectionDetails() {
    return new JdbcConnectionDetails() {
      @Override
      public String getJdbcUrl() {
        return "jdbc:postgresql://127.0.0.1:1/unreachable?connectTimeout=1&socketTimeout=1";
      }

      @Override
      public String getUsername() {
        return "nobody";
      }

      @Override
      public String getPassword() {
        return "nothing";
      }
    };
  }
}
