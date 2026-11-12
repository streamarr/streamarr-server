package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.support.BoundedTask;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Bounded connection pool close tests")
class BoundedConnectionPoolCloseTest {

  // The pool's own close waits its 30 s login timeout for a connection that it is opening.
  private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

  private final BoundedConnectionPoolClose boundedClose = new BoundedConnectionPoolClose();
  private final List<Socket> accepted = new CopyOnWriteArrayList<>();
  private final CountDownLatch connectionOpening = new CountDownLatch(1);
  // The pool closes its metrics tracker as the last step of its close.
  private final CountDownLatch poolCloseEnded = new CountDownLatch(1);
  private ServerSocket silentDatabase;
  private Thread accepting;

  // A database that accepts a connection and never answers the login.
  @BeforeEach
  void setUp() throws IOException {
    silentDatabase = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    accepting =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    accepted.add(silentDatabase.accept());
                    connectionOpening.countDown();
                  } catch (IOException _) {
                    // The test closed the socket.
                  }
                });
  }

  @AfterEach
  void tearDown() throws Exception {
    silentDatabase.close();
    accepting.join(CLOSE_WAIT);
    for (var socket : accepted) {
      socket.close();
    }
  }

  @Test
  @DisplayName(
      "Should stop waiting for the pool's close, and end that close, when the database does not"
          + " answer")
  void shouldStopWaitingForThePoolsCloseAndEndThatCloseWhenTheDatabaseDoesNotAnswer()
      throws Exception {
    var pool = poolOfTheSilentDatabase();
    assertThat(connectionOpening.await(CLOSE_WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

    BoundedTask.runWithin(
        CLOSE_WAIT, () -> boundedClose.postProcessBeforeDestruction(pool, "dataSource"));

    assertThat(poolCloseEnded.await(CLOSE_WAIT.toSeconds(), TimeUnit.SECONDS))
        .as("The pool's own close ends at its interrupt, not after its login timeout")
        .isTrue();
  }

  private HikariDataSource poolOfTheSilentDatabase() {
    var configuration = new HikariConfig();
    configuration.setJdbcUrl(
        "jdbc:postgresql://127.0.0.1:" + silentDatabase.getLocalPort() + "/streamarr");
    configuration.setUsername("streamarr");
    configuration.setPassword("streamarr");
    configuration.setMinimumIdle(1);
    // The pool starts without a connection and opens one in the background.
    configuration.setInitializationFailTimeout(-1);
    configuration.setMetricsTrackerFactory((_, _) -> new PoolCloseSignal(poolCloseEnded));
    return new HikariDataSource(configuration);
  }

  // Every method of the tracker has a default, so it cannot be a lambda.
  private record PoolCloseSignal(CountDownLatch poolCloseEnded) implements IMetricsTracker {
    @Override
    public void close() {
      poolCloseEnded.countDown();
    }
  }
}
