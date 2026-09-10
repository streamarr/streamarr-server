package com.streamarr.server.services.auth;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitBlockedBackendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@DisplayName("Credential Attempt Clock Ordering Integration Tests")
class CredentialAttemptClockOrderingIT extends AbstractIntegrationTest {

  @Autowired private CredentialAttemptGate gate;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final UUID accountId = UUID.randomUUID();
  private final CredentialAttemptTarget target =
      CredentialAttemptTarget.builder()
          .kind(CredentialKind.ACCOUNT_LOGIN)
          .accountId(accountId)
          .ipAddress("192.0.2.100")
          .build();

  @AfterEach
  void deleteJournalEntries() {
    jdbcTemplate.update("DELETE FROM credential_attempt WHERE account_id = ?", accountId);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(JournalWrite.class)
  @DisplayName("Should timestamp journal write when target lock has been acquired")
  void shouldTimestampJournalWriteWhenTargetLockHasBeenAcquired(JournalWrite write)
      throws Exception {
    var reservation = gate.reserve(target);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      lockTarget(holder);
      var queued =
          executor.submit(
              () ->
                  switch (write) {
                    case RESERVATION -> gate.reserve(target).id();
                    case COMPLETION -> {
                      gate.complete(reservation, CredentialAttemptResult.FAILED);
                      yield reservation.id();
                    }
                  });

      awaitBlockedBackendPid(holder, backendPid(holder), "advisory");
      var releasedAt = postgresTime(holder);
      holder.commit();

      var attemptId = queued.get(10, TimeUnit.SECONDS);
      assertThat(journaledAt(attemptId, write)).isAfterOrEqualTo(releasedAt);
    }
  }

  private void lockTarget(Connection holder) throws SQLException {
    try (var statement =
        holder.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtextextended('credential-attempt:' || lower(?), 0))")) {
      statement.setString(1, "ACCOUNT_LOGIN:" + accountId + ":null:null");
      statement.execute();
    }
  }

  private static Instant postgresTime(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT clock_timestamp()")) {
      assertThat(rows.next()).isTrue();
      return rows.getObject(1, OffsetDateTime.class).toInstant();
    }
  }

  private Instant journaledAt(UUID attemptId, JournalWrite write) {
    var column =
        switch (write) {
          case RESERVATION -> "attempted_at";
          case COMPLETION -> "completed_at";
        };
    return jdbcTemplate.queryForObject(
        "SELECT " + column + " FROM credential_attempt WHERE id = ?",
        (rows, _) -> rows.getObject(1, OffsetDateTime.class).toInstant(),
        attemptId);
  }

  private enum JournalWrite {
    RESERVATION,
    COMPLETION
  }
}
