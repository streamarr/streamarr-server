package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("IntegrationTest")
@DisplayName("Password Change Login Race Integration Tests")
@Import(PasswordChangeLoginRaceIT.PasswordEncoderTestConfig.class)
class PasswordChangeLoginRaceIT extends AbstractIntegrationTest {

  @Autowired private LoginService loginService;
  @Autowired private PasswordChangeService passwordChangeService;
  @Autowired private RefreshTokenService refreshTokenService;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private PausingPasswordEncoder passwordEncoder;
  @Autowired private DataSource dataSource;

  private UserAccount account;

  @AfterEach
  void deleteAccountAndCascades() {
    passwordEncoder.releaseLogin();
    if (account != null) {
      userAccountRepository.deleteById(account.getId());
    }
  }

  @Test
  @DisplayName(
      "Should reject an old-password login when password change completes after verification")
  void shouldRejectOldPasswordLoginWhenPasswordChangeCompletesAfterVerification() throws Exception {
    var oldPassword = UUID.randomUUID().toString();
    var newPassword = UUID.randomUUID().toString();
    account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(oldPassword))
                .build());
    var caller = refreshTokenService.createSession(account, "password-change-device").session();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var login =
          executor.submit(
              () -> {
                passwordEncoder.pauseAfterNextSuccessfulMatch();
                return loginService.login(
                    LoginCommand.builder()
                        .email(account.getEmail())
                        .password(oldPassword)
                        .deviceName("racing-login-device")
                        .source("race-test")
                        .build());
              });

      passwordEncoder.awaitLoginVerified();

      passwordChangeService.changePassword(
          ChangePasswordCommand.builder()
              .accountId(account.getId())
              .sessionId(caller.getId())
              .currentPassword(oldPassword)
              .newPassword(newPassword)
              .build());

      passwordEncoder.releaseLogin();

      assertThatThrownBy(() -> login.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(InvalidCredentialsException.class);
      assertThat(authSessionRepository.findByAccountId(account.getId()))
          .extracting("id")
          .containsExactly(caller.getId());
    } finally {
      passwordEncoder.releaseLogin();
    }
  }

  @Test
  @DisplayName(
      "Should reject an old-password login when password change completes after upgrade check")
  void shouldRejectOldPasswordLoginWhenPasswordChangeCompletesAfterUpgradeCheck() throws Exception {
    var oldPassword = UUID.randomUUID().toString();
    var newPassword = UUID.randomUUID().toString();
    account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(oldPassword))
                .build());
    var caller = refreshTokenService.createSession(account, "password-change-device").session();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var login =
          executor.submit(
              () -> {
                passwordEncoder.pauseAfterNextUpgradeCheck();
                return loginService.login(
                    LoginCommand.builder()
                        .email(account.getEmail())
                        .password(oldPassword)
                        .deviceName("racing-login-device")
                        .source("race-test")
                        .build());
              });

      passwordEncoder.awaitLoginUpgradeChecked();

      passwordChangeService.changePassword(
          ChangePasswordCommand.builder()
              .accountId(account.getId())
              .sessionId(caller.getId())
              .currentPassword(oldPassword)
              .newPassword(newPassword)
              .build());

      passwordEncoder.releaseLogin();

      assertThatThrownBy(() -> login.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(InvalidCredentialsException.class);
      assertThat(authSessionRepository.findByAccountId(account.getId()))
          .extracting("id")
          .containsExactly(caller.getId());
    } finally {
      passwordEncoder.releaseLogin();
    }
  }

  @Test
  @DisplayName("Should revoke old-password login when login commits before waiting password change")
  void shouldRevokeOldPasswordLoginWhenLoginCommitsBeforeWaitingPasswordChange() throws Exception {
    var oldPassword = UUID.randomUUID().toString();
    var newPassword = UUID.randomUUID().toString();
    account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(oldPassword))
                .build());
    var caller = refreshTokenService.createSession(account, "password-change-device").session();
    var tableLocked = new CountDownLatch(1);
    var releaseTable = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker = executor.submit(() -> holdAuthSessionTableLock(tableLocked, releaseTable));
      assertThat(tableLocked.await(10, TimeUnit.SECONDS))
          .as("auth-session table should be locked before the racing login")
          .isTrue();

      var login =
          executor.submit(
              () ->
                  loginService.login(
                      LoginCommand.builder()
                          .email(account.getEmail())
                          .password(oldPassword)
                          .deviceName("racing-login-device")
                          .source("race-test")
                          .build()));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(hasWaitingAuthSessionInsert())
                      .as("login should hold the account lock while its session insert waits")
                      .isTrue());

      var passwordChange =
          executor.submit(
              () ->
                  passwordChangeService.changePassword(
                      ChangePasswordCommand.builder()
                          .accountId(account.getId())
                          .sessionId(caller.getId())
                          .currentPassword(oldPassword)
                          .newPassword(newPassword)
                          .build()));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(hasPasswordChangeWaitingForAccountLock())
                      .as("password change should wait for the login's account lock")
                      .isTrue());

      releaseTable.countDown();
      var loginResult = login.get(10, TimeUnit.SECONDS);
      passwordChange.get(10, TimeUnit.SECONDS);
      blocker.get(10, TimeUnit.SECONDS);

      var racedSession =
          authSessionRepository.findById(loginResult.session().getId()).orElseThrow();
      assertThat(racedSession.getRevokedAt()).isNotNull();
      assertThat(racedSession.getRevokedReason())
          .isEqualTo(SessionRevocationReason.PASSWORD_CHANGE);
      assertThat(authSessionRepository.findById(caller.getId()).orElseThrow().getRevokedAt())
          .isNull();
    } finally {
      releaseTable.countDown();
    }
  }

  private void holdAuthSessionTableLock(CountDownLatch tableLocked, CountDownLatch releaseTable) {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("LOCK TABLE auth_session IN ACCESS EXCLUSIVE MODE");
      tableLocked.countDown();
      if (!releaseTable.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("racing login did not release the auth-session table lock");
      }
      connection.rollback();
    } catch (Exception exception) {
      throw new AssertionError("could not coordinate the auth-session table lock", exception);
    }
  }

  private boolean hasWaitingAuthSessionInsert() {
    return queryBoolean(
        """
        SELECT EXISTS (
          SELECT 1
          FROM pg_locks AS lock
          JOIN pg_class AS relation ON relation.oid = lock.relation
          WHERE relation.relname = 'auth_session'
            AND lock.mode = 'RowExclusiveLock'
            AND NOT lock.granted
        )
        """);
  }

  private boolean hasPasswordChangeWaitingForAccountLock() {
    return queryBoolean(
        """
        SELECT EXISTS (
          SELECT 1
          FROM pg_stat_activity
          WHERE wait_event_type = 'Lock'
            AND wait_event = 'transactionid'
            AND query ILIKE '%user_account%'
            AND query ILIKE '%for update%'
        )
        """);
  }

  private boolean queryBoolean(String sql) {
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql);
        var result = statement.executeQuery()) {
      result.next();
      return result.getBoolean(1);
    } catch (Exception exception) {
      throw new AssertionError("could not inspect PostgreSQL lock state", exception);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class PasswordEncoderTestConfig {

    @Bean
    @Primary
    PausingPasswordEncoder pausingPasswordEncoder(
        @Qualifier("passwordEncoder") PasswordEncoder delegate) {
      return new PausingPasswordEncoder(delegate);
    }
  }

  static final class PausingPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final ThreadLocal<PausePoint> pausePoint = new ThreadLocal<>();
    private final AtomicReference<PauseGate> pauseGate = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> pausePrepared =
        new AtomicReference<>(new CountDownLatch(1));

    private PausingPasswordEncoder(PasswordEncoder delegate) {
      this.delegate = delegate;
    }

    void pauseAfterNextSuccessfulMatch() {
      preparePause(PausePoint.SUCCESSFUL_MATCH);
    }

    void pauseAfterNextUpgradeCheck() {
      preparePause(PausePoint.UPGRADE_CHECK);
    }

    void awaitLoginVerified() throws InterruptedException {
      assertThat(pausePrepared.get().await(10, TimeUnit.SECONDS))
          .as("old-password login should prepare the verified interleaving point")
          .isTrue();
      assertThat(currentGate().loginPaused().await(10, TimeUnit.SECONDS))
          .as("old-password login should reach the verified interleaving point")
          .isTrue();
    }

    void awaitLoginUpgradeChecked() throws InterruptedException {
      assertThat(pausePrepared.get().await(10, TimeUnit.SECONDS))
          .as("old-password login should prepare the post-lock interleaving point")
          .isTrue();
      assertThat(currentGate().loginPaused().await(10, TimeUnit.SECONDS))
          .as("old-password login should reach the post-lock interleaving point")
          .isTrue();
    }

    void releaseLogin() {
      var gate = pauseGate.getAndSet(null);
      if (gate != null) {
        gate.continueLogin().countDown();
      }
      pausePrepared.set(new CountDownLatch(1));
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      var matches = delegate.matches(rawPassword, encodedPassword);
      if (matches) {
        pauseIfRequested(PausePoint.SUCCESSFUL_MATCH);
      }
      return matches;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
      var upgradeEncoding = delegate.upgradeEncoding(encodedPassword);
      pauseIfRequested(PausePoint.UPGRADE_CHECK);
      return upgradeEncoding;
    }

    private void preparePause(PausePoint point) {
      pausePoint.set(point);
      pauseGate.set(new PauseGate(new CountDownLatch(1), new CountDownLatch(1)));
      pausePrepared.get().countDown();
    }

    private PauseGate currentGate() {
      var gate = pauseGate.get();
      if (gate == null) {
        throw new AssertionError("no login pause has been prepared");
      }
      return gate;
    }

    private void pauseIfRequested(PausePoint point) {
      if (pausePoint.get() != point) {
        return;
      }

      pausePoint.remove();
      var gate = currentGate();
      gate.loginPaused().countDown();
      try {
        if (!gate.continueLogin().await(10, TimeUnit.SECONDS)) {
          throw new AssertionError("password change did not release the paused login");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("paused login was interrupted", e);
      }
    }

    private enum PausePoint {
      SUCCESSFUL_MATCH,
      UPGRADE_CHECK
    }

    private record PauseGate(CountDownLatch loginPaused, CountDownLatch continueLogin) {}
  }
}
