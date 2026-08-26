package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.exceptions.CredentialAttemptUnavailableException;
import com.streamarr.server.support.AuthTestSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@DisplayName("Credential Attempt Gate Integration Tests")
class CredentialAttemptGateIT extends AbstractIntegrationTest {

  private static final String IP_ADDRESS = "192.0.2.15";
  private static final String IPV6_ADDRESS = "fe80:0:0:0:0:0:0:1";
  // PostgreSQL renders inet text in its compressed form.
  private static final String IPV6_ADDRESS_AS_STORED = "fe80::1";

  @Autowired private CredentialAttemptGate gate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private AuthTestSupport authTestSupport;

  @AfterEach
  void deleteAttempts() {
    jdbcTemplate.update(
        "DELETE FROM credential_attempt WHERE host(ip_address) IN (?, ?)",
        IP_ADDRESS,
        IPV6_ADDRESS_AS_STORED);
  }

  @Test
  @DisplayName("Should persist the Account and IP address when a login attempt fails")
  void shouldPersistAccountAndIpAddressWhenLoginAttemptFails() {
    var before = Instant.now();
    var accountId = UUID.randomUUID();
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.ACCOUNT_LOGIN)
            .accountId(accountId)
            .ipAddress(IP_ADDRESS)
            .build();

    var reservation = gate.reserve(target);
    gate.complete(reservation, CredentialAttemptResult.FAILED);

    var row =
        jdbcTemplate.queryForMap(
            """
            SELECT id, credential_kind::text AS credential_kind, account_id, profile_id,
                   credential_id, host(ip_address) AS ip_address, attempted_at, completed_at,
                   result::text AS result
              FROM credential_attempt
             WHERE host(ip_address) = ?
            """,
            IP_ADDRESS);
    assertThat(row)
        .containsEntry("id", reservation.id())
        .containsEntry("credential_kind", "ACCOUNT_LOGIN")
        .containsEntry("account_id", accountId)
        .containsEntry("profile_id", null)
        .containsEntry("credential_id", null)
        .containsEntry("ip_address", IP_ADDRESS)
        .containsEntry("result", "FAILED");
    var attemptedAt = ((Timestamp) row.get("attempted_at")).toInstant();
    assertThat(attemptedAt).isAfterOrEqualTo(before);
    assertThat(((Timestamp) row.get("completed_at")).toInstant()).isAfterOrEqualTo(attemptedAt);
  }

  @Test
  @DisplayName("Should persist the client address when it is IPv6")
  void shouldPersistClientAddressWhenItIsIpv6() {
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.ACCOUNT_LOGIN)
            .accountId(UUID.randomUUID())
            .ipAddress(IPV6_ADDRESS)
            .build();

    var reservation = gate.reserve(target);
    gate.complete(reservation, CredentialAttemptResult.FAILED);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT host(ip_address) FROM credential_attempt WHERE id = ?",
                String.class,
                reservation.id()))
        .isEqualTo(IPV6_ADDRESS_AS_STORED);
  }

  @Test
  @DisplayName("Should fail closed when the journal cannot be locked within the lock timeout")
  void shouldFailClosedWhenJournalCannotBeLockedWithinLockTimeout() throws Exception {
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.ACCOUNT_LOGIN)
            .accountId(UUID.randomUUID())
            .ipAddress(IP_ADDRESS)
            .build();

    try (var blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement.execute("LOCK TABLE credential_attempt IN ACCESS EXCLUSIVE MODE");
      }

      var started = Instant.now();
      assertThatThrownBy(() -> gate.reserve(target))
          .isInstanceOf(CredentialAttemptUnavailableException.class);
      // The wait is bounded by the two-second lock_timeout, not by the caller's patience.
      assertThat(Duration.between(started, Instant.now()))
          .isGreaterThanOrEqualTo(Duration.ofSeconds(2))
          .isLessThan(Duration.ofSeconds(30));
      blocker.rollback();
    }

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM credential_attempt WHERE host(ip_address) = ?",
                Integer.class,
                IP_ADDRESS))
        .isZero();
  }

  @Test
  @DisplayName("Should fail closed when the target's advisory lock is held during completion")
  void shouldFailClosedWhenTargetsAdvisoryLockIsHeldDuringCompletion() throws Exception {
    var accountId = UUID.randomUUID();
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.ACCOUNT_LOGIN)
            .accountId(accountId)
            .ipAddress(IP_ADDRESS)
            .build();
    var reservation = gate.reserve(target);

    try (var holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement(
              "SELECT pg_advisory_xact_lock(hashtextextended('credential-attempt:' || lower(?), 0))")) {
        statement.setString(1, "ACCOUNT_LOGIN:" + accountId + ":null:null");
        statement.execute();
      }

      // Completion serializes on the same key as admission, so it cannot slip between a
      // concurrent admission's reads; a held lock fails closed rather than completing.
      assertThatThrownBy(() -> gate.complete(reservation, CredentialAttemptResult.FAILED))
          .isInstanceOf(CredentialAttemptUnavailableException.class);
      holder.rollback();
    }

    gate.complete(reservation, CredentialAttemptResult.FAILED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT result::text FROM credential_attempt WHERE id = ?",
                String.class,
                reservation.id()))
        .isEqualTo("FAILED");
  }

  @Test
  @DisplayName("Should keep the journal row when its Account is deleted")
  void shouldKeepTheJournalRowWhenItsAccountIsDeleted() {
    var account = authTestSupport.createAccount();
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.ACCOUNT_LOGIN)
            .accountId(account.getId())
            .ipAddress(IP_ADDRESS)
            .build();
    gate.complete(gate.reserve(target), CredentialAttemptResult.FAILED);

    authTestSupport.deleteAccount(account.getId());

    // Security history has no foreign key to its subjects (ADR 0028).
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM credential_attempt WHERE account_id = ?",
                Integer.class,
                account.getId()))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Should journal a credential that no row backs when the code id is unknown")
  void shouldJournalCredentialThatNoRowBacksWhenCodeIdIsUnknown() {
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.PASSWORD_RESET_CODE)
            .credentialId(UUID.randomUUID())
            .ipAddress(IP_ADDRESS)
            .build();

    gate.complete(gate.reserve(target), CredentialAttemptResult.FAILED);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM credential_attempt WHERE credential_id = ?",
                Integer.class,
                target.credentialId()))
        .isEqualTo(1);
  }
}
