package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
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

  @AfterEach
  void deleteAttempts() {
    jdbcTemplate.update(
        "DELETE FROM credential_attempt WHERE host(ip_address) IN (?, ?)",
        IP_ADDRESS,
        IPV6_ADDRESS_AS_STORED);
  }

  @Test
  @DisplayName("Should persist a failed login attempt with its Account and IP address")
  void shouldPersistAFailedLoginAttemptWithItsAccountAndIpAddress() {
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
    assertThat(row.get("id")).isEqualTo(reservation.id());
    assertThat(row.get("credential_kind")).isEqualTo("ACCOUNT_LOGIN");
    assertThat(row.get("account_id")).isEqualTo(accountId);
    assertThat(row.get("profile_id")).isNull();
    assertThat(row.get("credential_id")).isNull();
    assertThat(row.get("ip_address")).isEqualTo(IP_ADDRESS);
    assertThat(((Timestamp) row.get("attempted_at")).toInstant()).isAfterOrEqualTo(before);
    assertThat(((Timestamp) row.get("completed_at")).toInstant())
        .isAfterOrEqualTo(((Timestamp) row.get("attempted_at")).toInstant());
    assertThat(row.get("result")).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("Should persist an IPv6 client address")
  void shouldPersistAnIpv6ClientAddress() {
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
}
