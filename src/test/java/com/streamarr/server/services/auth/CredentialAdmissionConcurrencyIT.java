package com.streamarr.server.services.auth;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.CredentialAttemptRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("IntegrationTest")
@DisplayName("Credential Admission Concurrency Tests")
class CredentialAdmissionConcurrencyIT extends AbstractIntegrationTest {
  @Autowired private AuthTestSupport fixtures;
  @Autowired private LoginService login;
  @Autowired private LoginCompletionService completion;
  @Autowired private UserAccountRepository accounts;
  @Autowired private AuthSessionRepository sessions;
  @Autowired private CredentialAttemptRepository attempts;
  @Autowired private PasswordEncoder encoder;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;
  private UserAccount account;

  @AfterEach
  void deleteAccount() {
    if (account != null) {
      jdbc.update("DELETE FROM credential_attempt WHERE account_id = ?", account.getId());
      fixtures.deleteAccount(account.getId());
    }
  }

  @Test
  @DisplayName("Should admit one login when two instances contend for the last attempt slot")
  void shouldAdmitOneLoginWhenTwoInstancesContendForLastAttemptSlot() throws Exception {
    account = fixtures.createAccount();
    var wrong = command("wrong");
    for (var i = 0; i < 4; i++) {
      assertThatThrownBy(() -> login.login(wrong)).isInstanceOf(InvalidCredentialsException.class);
    }

    var failedIds =
        jdbc.queryForList(
            "SELECT id FROM credential_attempt WHERE account_id = ?", UUID.class, account.getId());
    var admittedOrBlocked = new CountDownLatch(2);
    var releaseVerification = new CountDownLatch(1);
    var probe = new PausedPasswordEncoder(encoder, admittedOrBlocked, releaseVerification);
    var first = instance(probe);
    var second = instance(probe);
    var correct = command(fixtures.password());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var statement = holder.createStatement()) {
        // SHARE permits history reads but parks the insert before it can consume the final slot.
        statement.execute("LOCK TABLE credential_attempt IN SHARE MODE");
      }

      var holderPid = backendPid(holder);
      var one = executor.submit(() -> tryLogin(first, correct, admittedOrBlocked));
      var two = executor.submit(() -> tryLogin(second, correct, admittedOrBlocked));
      try {
        await().atMost(Duration.ofSeconds(5)).until(() -> waitersBehind(jdbc, holderPid, "%") == 2);
        holder.rollback();
        awaitLatch(admittedOrBlocked);
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM credential_attempt WHERE account_id = ?",
                    Integer.class,
                    account.getId()))
            .isEqualTo(5);
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM credential_attempt WHERE account_id = ? AND result IS NULL",
                    Integer.class,
                    account.getId()))
            .isEqualTo(1);
      } finally {
        holder.rollback();
        releaseVerification.countDown();
      }

      assertThat(one.get(10, TimeUnit.SECONDS)).isNotEqualTo(two.get(10, TimeUnit.SECONDS));
    }

    assertThat(sessions.findByAccountId(account.getId())).hasSize(1);
    assertThat(
            jdbc.queryForList(
                "SELECT id FROM credential_attempt WHERE account_id = ?",
                UUID.class,
                account.getId()))
        .hasSize(5)
        .containsAll(failedIds);
    assertThat(
            jdbc.queryForList(
                "SELECT result::text FROM credential_attempt WHERE account_id = ? ORDER BY attempted_at",
                String.class,
                account.getId()))
        .containsExactly("FAILED", "FAILED", "FAILED", "FAILED", "SUCCEEDED");
  }

  private LoginService instance(PasswordEncoder passwordEncoder) {
    return new LoginService(
        accounts,
        completion,
        passwordEncoder,
        new CredentialAttemptGate(attempts, new StandardCredentialAttemptPolicyProvider()),
        new PasswordTimingEqualizer(passwordEncoder));
  }

  private LoginCommand command(String password) {
    return LoginCommand.builder()
        .email(account.getEmail())
        .password(password)
        .deviceName("contender")
        .ipAddress("192.0.2.37")
        .build();
  }

  private static boolean tryLogin(
      LoginService service, LoginCommand command, CountDownLatch admittedOrBlocked) {
    try {
      service.login(command);
      return true;
    } catch (TooManyLoginAttemptsException _) {
      admittedOrBlocked.countDown();
      return false;
    }
  }

  private record PausedPasswordEncoder(
      PasswordEncoder delegate, CountDownLatch admittedOrBlocked, CountDownLatch release)
      implements PasswordEncoder {
    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      admittedOrBlocked.countDown();
      awaitLatch(release);
      return delegate.matches(rawPassword, encodedPassword);
    }
  }
}
