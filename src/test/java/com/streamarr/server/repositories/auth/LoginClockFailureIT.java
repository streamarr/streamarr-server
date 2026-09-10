package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.CredentialAttemptUnavailableException;
import com.streamarr.server.services.auth.LoginCommand;
import com.streamarr.server.services.auth.LoginService;
import com.streamarr.server.support.AuthTestSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@Tag("IntegrationTest")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Login Clock Failure Tests")
@Import(LoginClockFailureIT.ClockConfiguration.class)
class LoginClockFailureIT extends AbstractIntegrationTest {
  @Autowired private AuthTestSupport fixtures;
  @Autowired private LoginService login;
  @Autowired private ClockQueryFailure clockQuery;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private AuthSessionRepository sessions;
  private UserAccount account;

  @AfterEach
  void deleteAccount() {
    clockQuery.fail.set(false);
    if (account != null) {
      fixtures.deleteAccount(account.getId());
    }
  }

  @Test
  @DisplayName("Should fail closed without a session when the database clock query fails")
  void shouldFailClosedWithoutSessionWhenDatabaseClockQueryFails() {
    account = fixtures.createAccount();
    var command =
        LoginCommand.builder()
            .email(account.getEmail())
            .password(fixtures.password())
            .deviceName("clock-outage")
            .ipAddress("192.0.2.38")
            .build();
    clockQuery.fail.set(true);
    assertThatThrownBy(() -> login.login(command))
        .isInstanceOf(CredentialAttemptUnavailableException.class);
    assertThat(clockQuery.fail).isFalse();
    assertThat(sessions.findByAccountId(account.getId())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM credential_attempt WHERE account_id = ?",
                Integer.class,
                account.getId()))
        .isZero();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    ClockQueryFailure clockQueryFailure() {
      return new ClockQueryFailure();
    }

    @Bean
    @Primary
    CredentialAttemptClock failingClock(DSLContext dsl, ClockQueryFailure failure) {
      return new PostgresCredentialAttemptClock(
          DSL.using(dsl.configuration().derive(new DefaultExecuteListenerProvider(failure))));
    }
  }

  static final class ClockQueryFailure implements ExecuteListener {
    private final AtomicBoolean fail = new AtomicBoolean();

    @Override
    public void executeStart(ExecuteContext context) {
      if (fail.compareAndSet(true, false)) {
        assertThat(context.sql()).contains("clock_timestamp");
        throw new DataAccessResourceFailureException("Database clock unavailable");
      }
    }
  }
}
