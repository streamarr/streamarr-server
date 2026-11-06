package com.streamarr.server.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@DisplayName("Pooled Connection Session Settings Integration Tests")
class PooledConnectionSessionSettingsIT extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("Should bound idle transactions and lock waits when a pooled connection opens")
  void shouldBoundIdleTransactionsAndLockWaitsWhenAPooledConnectionOpens() {
    var settings =
        jdbcTemplate.queryForMap(
            """
            SELECT current_setting('idle_in_transaction_session_timeout') AS idle_in_transaction,
                   current_setting('lock_timeout') AS lock_wait
            """);

    assertThat(settings)
        .containsEntry("idle_in_transaction", "1min")
        .containsEntry("lock_wait", "30s");
  }
}
