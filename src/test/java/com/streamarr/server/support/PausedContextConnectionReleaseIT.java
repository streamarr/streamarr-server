package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;

@Tag("IntegrationTest")
@DisplayName("Paused Context Connection Release Integration Tests")
class PausedContextConnectionReleaseIT extends AbstractIntegrationTest {

  @Autowired private ConfigurableApplicationContext context;
  @Autowired private HikariDataSource dataSource;

  @Test
  @DisplayName("Should hold no database connections when the cached context is paused")
  void shouldHoldNoDatabaseConnectionsWhenTheCachedContextIsPaused() throws SQLException {
    dataSource.getConnection().close();
    var pool = dataSource.getHikariPoolMXBean();
    assertThat(pool.getIdleConnections()).isPositive();

    context.pause();
    try {
      await()
          .during(Duration.ofMillis(500))
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(pool.getTotalConnections()).isZero());
    } finally {
      context.restart();
    }
  }
}
