package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.CredentialAttemptMetadata;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("IntegrationTest")
@DisplayName("Credential Attempt Clock Skew Integration Tests")
@Import(CredentialAttemptClockSkewIT.ClockConfiguration.class)
class CredentialAttemptClockSkewIT extends AbstractIntegrationTest {

  @Autowired private LoginService loginService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private MutableClock applicationClock;
  @Autowired private CredentialAttemptGate gate;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UserAccount account;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM credential_attempt WHERE host(ip_address) = ?", "192.0.2.98");
    if (account != null) {
      authTestSupport.deleteAccount(account.getId());
    }
  }

  @Test
  @DisplayName(
      "Should retain failed verifications when the application clock is behind a prior success")
  void shouldRetainFailedVerificationsWhenApplicationClockIsBehindPriorSuccess() {
    var metadata =
        CredentialAttemptMetadata.builder()
            .kind(CredentialKind.ACCOUNT_LOGIN)
            .accountId(UUID.randomUUID())
            .ipAddress("192.0.2.98")
            .build();
    gate.attempt(metadata, () -> "verified");
    applicationClock.advance(Duration.ofSeconds(-1));

    for (var attempt = 0; attempt < 5; attempt++) {
      assertThatThrownBy(
              () ->
                  gate.attempt(
                      metadata,
                      () -> {
                        throw new InvalidCredentialsException();
                      }))
          .isInstanceOf(InvalidCredentialsException.class);
    }

    assertThatThrownBy(() -> gate.attempt(metadata, () -> "must not verify"))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName(
      "Should throttle login failures when the application clock is behind a prior success")
  void shouldThrottleLoginFailuresWhenApplicationClockIsBehindPriorSuccess() {
    var password = UUID.randomUUID().toString();
    account =
        authTestSupport.createAccount(
            builder -> builder.passwordHash(passwordEncoder.encode(password)));
    var command =
        LoginCommand.builder()
            .email(account.getEmail())
            .password(password)
            .deviceName("clock-skew-test")
            .ipAddress("192.0.2.98");

    loginService.login(command.build());
    applicationClock.advance(Duration.ofSeconds(-1));
    var wrongPassword = command.password("incorrect-password").build();

    for (var attempt = 0; attempt < 5; attempt++) {
      assertThatThrownBy(() -> loginService.login(wrongPassword))
          .isInstanceOf(InvalidCredentialsException.class);
    }

    assertThatThrownBy(() -> loginService.login(wrongPassword))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @TestConfiguration
  static class ClockConfiguration {

    @Bean
    @Primary
    MutableClock applicationClock() {
      return new MutableClock(new AtomicReference<>(Instant.now()));
    }
  }
}
