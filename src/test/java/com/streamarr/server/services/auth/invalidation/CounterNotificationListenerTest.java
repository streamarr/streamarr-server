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
  @DisplayName("Should stay running and unlistening when database unreachable")
  void shouldStayRunningAndUnlisteningWhenDatabaseUnreachable() {
    var cache = new TokenVersionCache(new FakeVersionCounterReader());
    var listener = new CounterNotificationListener(cache, unreachableConnectionDetails());

    listener.start();
    try {
      // The reconnect loop must survive connection failures: alive, backing off, never listening.
      await().atMost(Duration.ofSeconds(5)).until(listener::isRunning);
      assertThat(listener.isListening()).isFalse();
      assertThat(listener.isRunning()).isTrue();
    } finally {
      listener.stop();
    }

    await().atMost(Duration.ofSeconds(5)).until(() -> !listener.isRunning());
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
