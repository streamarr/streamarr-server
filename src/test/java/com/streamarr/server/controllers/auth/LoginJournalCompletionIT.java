package com.streamarr.server.controllers.auth;

import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.CredentialAttemptUnavailableException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.services.auth.LoginCommand;
import com.streamarr.server.services.auth.LoginService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.HeldRowLock;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("IntegrationTest")
@DisplayName("Login Journal Completion Integration Tests")
@Import(LoginJournalCompletionIT.PasswordEncoderConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginJournalCompletionIT extends AbstractIntegrationTest {

  // The journal sets its own lock_timeout, since ADR 0028 bounds its locks. A transaction that
  // loses it waits for the server-wide lock_timeout instead.
  private static final Duration JOURNAL_LOCK_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration SERVER_LOCK_TIMEOUT = Duration.ofSeconds(30);

  @Autowired private LoginService loginService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private AuthSessionRepository sessionRepository;
  @Autowired private CompletionBlockingPasswordEncoder passwordEncoder;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UserAccount account;
  private HeldRowLock journalLock;
  private int originalDigestCount;

  @BeforeEach
  void blockJournalCompletionAfterPasswordVerification() {
    account = authTestSupport.createAccount();
    originalDigestCount =
        jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_token", Integer.class);
    passwordEncoder.afterNextMatch(this::lockReservedAttempt);
  }

  @AfterEach
  void releaseLockAndDeleteAccount() throws SQLException {
    passwordEncoder.clear();
    if (journalLock != null) {
      journalLock.close();
    }

    if (account != null) {
      jdbcTemplate.update("DELETE FROM credential_attempt WHERE account_id = ?", account.getId());
      authTestSupport.deleteAccount(account.getId());
    }
  }

  @Test
  @DisplayName(
      "Should roll back the login session at the journal's lock timeout when its journal entry"
          + " stays locked")
  void shouldRollBackLoginSessionAtJournalLockTimeoutWhenJournalEntryStaysLocked() {
    var command =
        LoginCommand.builder()
            .email(account.getEmail())
            .password(authTestSupport.password())
            .deviceName("journal-outage")
            .ipAddress("192.0.2.99")
            .build();

    var started = Instant.now();
    assertThatThrownBy(() -> loginService.login(command))
        .isInstanceOf(CredentialAttemptUnavailableException.class);

    assertThat(Duration.between(started, Instant.now()))
        .as("the completion stops waiting on the locked journal row at the journal's own bound")
        .isGreaterThanOrEqualTo(JOURNAL_LOCK_TIMEOUT)
        .isLessThan(SERVER_LOCK_TIMEOUT);
    assertThat(sessionRepository.findByAccountId(account.getId())).isEmpty();
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_token", Integer.class))
        .isEqualTo(originalDigestCount);
  }

  @Test
  @DisplayName("Should hold no transaction or database connection when verifying a login password")
  void shouldHoldNoTransactionOrDatabaseConnectionWhenVerifyingLoginPassword() {
    passwordEncoder.afterNextMatch(
        () -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
        });

    var result =
        loginService.login(
            LoginCommand.builder()
                .email(account.getEmail())
                .password(authTestSupport.password())
                .deviceName("connection-check")
                .ipAddress("192.0.2.99")
                .build());

    assertThat(sessionRepository.findByAccountId(account.getId()))
        .singleElement()
        .isEqualTo(result.session());
  }

  private void lockReservedAttempt() {
    try {
      journalLock =
          lockRow(
              RowLockTarget.builder()
                  .dataSource(dataSource)
                  .table("credential_attempt")
                  .keyColumn("account_id")
                  .rowId(account.getId())
                  .build());
    } catch (SQLException failure) {
      throw new AssertionError("Could not lock the reserved credential attempt", failure);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class PasswordEncoderConfiguration {

    @Bean
    @Primary
    CompletionBlockingPasswordEncoder completionBlockingPasswordEncoder(
        @Qualifier("passwordEncoder") PasswordEncoder delegate) {
      return new CompletionBlockingPasswordEncoder(delegate);
    }
  }

  @RequiredArgsConstructor
  static final class CompletionBlockingPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final AtomicReference<Runnable> afterMatch = new AtomicReference<>();

    void afterNextMatch(Runnable action) {
      afterMatch.set(action);
    }

    void clear() {
      afterMatch.set(null);
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      var matched = delegate.matches(rawPassword, encodedPassword);
      var action = afterMatch.getAndSet(null);
      if (action != null) {
        action.run();
      }

      return matched;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
      return delegate.upgradeEncoding(encodedPassword);
    }
  }
}
