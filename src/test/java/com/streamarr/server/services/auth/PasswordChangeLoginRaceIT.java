package com.streamarr.server.services.auth;

import static com.streamarr.server.jooq.generated.tables.RefreshToken.REFRESH_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jooq.DSLContext;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  private UserAccount account;

  @AfterEach
  void deleteAccountAndCascades() {
    passwordEncoder.reset();
    if (account != null) {
      userAccountRepository.deleteById(account.getId());
    }
  }

  @Test
  @DisplayName("Should verify and hash passwords without a transaction-bound connection")
  void shouldVerifyAndHashPasswordsWithoutTransactionBoundConnection() {
    var oldPassword = UUID.randomUUID().toString();
    account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(oldPassword))
                .build());
    var caller = refreshTokenService.createSession(account, "password-change-device").session();
    passwordEncoder.observeNextPasswordChange();

    var result =
        passwordChangeService.changePassword(
            ChangePasswordCommand.builder()
                .accountId(account.getId())
                .sessionId(caller.getId())
                .currentPassword(oldPassword)
                .newPassword(UUID.randomUUID().toString())
                .build());

    assertThat(result.accessToken()).isNotNull();
    assertThat(passwordEncoder.matchObservation()).isEqualTo(TransactionObservation.NONE);
    assertThat(passwordEncoder.encodeObservation()).isEqualTo(TransactionObservation.NONE);
  }

  @Test
  @DisplayName("Should reject password change inside an ambient transaction")
  void shouldRejectPasswordChangeInsideAmbientTransaction() {
    var oldPassword = UUID.randomUUID().toString();
    account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(oldPassword))
                .build());
    var caller = refreshTokenService.createSession(account, "password-change-device").session();
    var transaction = new TransactionTemplate(transactionManager);
    var command =
        ChangePasswordCommand.builder()
            .accountId(account.getId())
            .sessionId(caller.getId())
            .currentPassword(oldPassword)
            .newPassword(UUID.randomUUID().toString())
            .build();
    passwordEncoder.observeNextPasswordChange();

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    _ -> passwordChangeService.changePassword(command)))
        .isInstanceOf(IllegalTransactionStateException.class)
        .hasMessage("Password changes require a non-transactional caller");
    assertThat(passwordEncoder.matchObservation()).isNull();
    assertThat(passwordEncoder.encodeObservation()).isNull();
  }

  @Test
  @DisplayName("Should reject password completion when caller session belongs to another account")
  void shouldRejectPasswordCompletionWhenCallerSessionBelongsToAnotherAccount() {
    var oldPassword = UUID.randomUUID().toString();
    account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(oldPassword))
                .build());
    var foreignAccount = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var foreignSession =
        refreshTokenService.createSession(foreignAccount, "foreign-password-device").session();

    try {
      assertThatThrownBy(
              () ->
                  passwordChangeService.changePassword(
                      ChangePasswordCommand.builder()
                          .accountId(account.getId())
                          .sessionId(foreignSession.getId())
                          .currentPassword(oldPassword)
                          .newPassword(UUID.randomUUID().toString())
                          .build()))
          .isInstanceOf(AuthenticationRequiredException.class);

      assertThat(
              authSessionRepository.findById(foreignSession.getId()).orElseThrow().getRevokedAt())
          .isNull();
      assertThat(
              passwordEncoder.matches(
                  oldPassword,
                  userAccountRepository.findById(account.getId()).orElseThrow().getPasswordHash()))
          .isTrue();
    } finally {
      userAccountRepository.deleteById(foreignAccount.getId());
    }
  }

  @Test
  @DisplayName("Should reject password completion when caller session was revoked")
  void shouldRejectPasswordCompletionWhenCallerSessionWasRevoked() {
    var oldPassword = UUID.randomUUID().toString();
    account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(oldPassword))
                .build());
    var caller = refreshTokenService.createSession(account, "revoked-password-device").session();
    refreshTokenService.logout(caller.getId());

    assertThatThrownBy(
            () ->
                passwordChangeService.changePassword(
                    ChangePasswordCommand.builder()
                        .accountId(account.getId())
                        .sessionId(caller.getId())
                        .currentPassword(oldPassword)
                        .newPassword(UUID.randomUUID().toString())
                        .build()))
        .isInstanceOf(AuthenticationRequiredException.class);

    assertThat(authSessionRepository.findByAccountId(account.getId()))
        .singleElement()
        .satisfies(session -> assertThat(session.getRevokedAt()).isNotNull());
    assertThat(
            passwordEncoder.matches(
                oldPassword,
                userAccountRepository.findById(account.getId()).orElseThrow().getPasswordHash()))
        .isTrue();
  }

  @Test
  @DisplayName("Should produce exactly one replacement when password changes race")
  void shouldProduceExactlyOneReplacementWhenPasswordChangesRace() throws Exception {
    var oldPassword = UUID.randomUUID().toString();
    var firstNewPassword = UUID.randomUUID().toString();
    var secondNewPassword = UUID.randomUUID().toString();
    account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(oldPassword))
                .build());
    var firstCaller = refreshTokenService.createSession(account, "first-password-device").session();
    var secondCaller =
        refreshTokenService.createSession(account, "second-password-device").session();
    passwordEncoder.pauseNextTwoEncodes();

    var successes = new ArrayList<PasswordChangeResult>();
    var failures = new ArrayList<Throwable>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(() -> changePassword(firstCaller.getId(), oldPassword, firstNewPassword));
      var second =
          executor.submit(
              () -> changePassword(secondCaller.getId(), oldPassword, secondNewPassword));

      passwordEncoder.awaitBothPasswordChangesHashed();
      passwordEncoder.releasePasswordChanges();
      collect(first, successes, failures);
      collect(second, successes, failures);
    } finally {
      passwordEncoder.releasePasswordChanges();
    }

    assertThat(successes).hasSize(1);
    assertThat(failures).singleElement().isInstanceOf(AuthenticationRequiredException.class);
    var sessions = authSessionRepository.findByAccountId(account.getId());
    assertThat(sessions).hasSize(3);
    assertThat(sessions)
        .filteredOn(session -> session.getRevokedAt() == null)
        .singleElement()
        .satisfies(
            replacement -> {
              assertThat(replacement.getId()).isNotIn(firstCaller.getId(), secondCaller.getId());
              assertThat(activeRefreshTokenCount(replacement.getId())).isEqualTo(1);
            });
    assertPasswordChangeRevoked(firstCaller.getId());
    assertPasswordChangeRevoked(secondCaller.getId());
    assertThat(activeRefreshTokenCount(firstCaller.getId())).isZero();
    assertThat(activeRefreshTokenCount(secondCaller.getId())).isZero();

    var storedPasswordHash =
        userAccountRepository.findById(account.getId()).orElseThrow().getPasswordHash();
    assertThat(
            List.of(
                passwordEncoder.matches(firstNewPassword, storedPasswordHash),
                passwordEncoder.matches(secondNewPassword, storedPasswordHash)))
        .containsExactlyInAnyOrder(true, false);
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
      assertCallerWasReplaced(caller.getId(), 2);
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
      assertCallerWasReplaced(caller.getId(), 2);
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
      assertCallerWasReplaced(caller.getId(), 3);
    } finally {
      releaseTable.countDown();
    }
  }

  private PasswordChangeResult changePassword(
      UUID callerSessionId, String oldPassword, String newPassword) {
    return passwordChangeService.changePassword(
        ChangePasswordCommand.builder()
            .accountId(account.getId())
            .sessionId(callerSessionId)
            .currentPassword(oldPassword)
            .newPassword(newPassword)
            .build());
  }

  private void collect(
      java.util.concurrent.Future<PasswordChangeResult> attempt,
      List<PasswordChangeResult> successes,
      List<Throwable> failures)
      throws Exception {
    try {
      successes.add(attempt.get(10, TimeUnit.SECONDS));
    } catch (ExecutionException exception) {
      failures.add(exception.getCause());
    }
  }

  private void assertCallerWasReplaced(UUID callerSessionId, int expectedSessionCount) {
    var sessions = authSessionRepository.findByAccountId(account.getId());
    assertThat(sessions).hasSize(expectedSessionCount);
    assertThat(sessions)
        .filteredOn(session -> session.getId().equals(callerSessionId))
        .singleElement()
        .satisfies(
            caller -> {
              assertThat(caller.getRevokedAt()).isNotNull();
              assertThat(caller.getRevokedReason())
                  .isEqualTo(SessionRevocationReason.PASSWORD_CHANGE);
            });
    assertThat(sessions)
        .filteredOn(session -> session.getRevokedAt() == null)
        .singleElement()
        .satisfies(replacement -> assertThat(replacement.getId()).isNotEqualTo(callerSessionId));
  }

  private void assertPasswordChangeRevoked(UUID sessionId) {
    assertThat(authSessionRepository.findById(sessionId).orElseThrow())
        .satisfies(
            session -> {
              assertThat(session.getRevokedAt()).isNotNull();
              assertThat(session.getRevokedReason())
                  .isEqualTo(SessionRevocationReason.PASSWORD_CHANGE);
            });
  }

  private int activeRefreshTokenCount(UUID sessionId) {
    return dsl.selectCount()
        .from(REFRESH_TOKEN)
        .where(REFRESH_TOKEN.SESSION_ID.eq(sessionId))
        .and(
            REFRESH_TOKEN.STATUS.eq(
                com.streamarr.server.jooq.generated.enums.RefreshTokenStatus.ACTIVE))
        .fetchSingle(0, int.class);
  }

  private void holdAuthSessionTableLock(CountDownLatch tableLocked, CountDownLatch releaseTable) {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("LOCK TABLE auth_session IN ACCESS EXCLUSIVE MODE");
      tableLocked.countDown();
      if (!releaseTable.await(30, TimeUnit.SECONDS)) {
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
        @Qualifier("passwordEncoder") PasswordEncoder delegate, DataSource dataSource) {
      return new PausingPasswordEncoder(delegate, dataSource);
    }
  }

  static final class PausingPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final DataSource dataSource;
    private final ThreadLocal<PausePoint> pausePoint = new ThreadLocal<>();
    private final AtomicReference<PauseGate> pauseGate = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> pausePrepared =
        new AtomicReference<>(new CountDownLatch(1));
    private final AtomicBoolean observePasswordChange = new AtomicBoolean();
    private final AtomicReference<TransactionObservation> matchObservation =
        new AtomicReference<>();
    private final AtomicReference<TransactionObservation> encodeObservation =
        new AtomicReference<>();
    private final AtomicReference<EncodeGate> encodeGate = new AtomicReference<>();

    private PausingPasswordEncoder(PasswordEncoder delegate, DataSource dataSource) {
      this.delegate = delegate;
      this.dataSource = dataSource;
    }

    void observeNextPasswordChange() {
      matchObservation.set(null);
      encodeObservation.set(null);
      observePasswordChange.set(true);
    }

    TransactionObservation matchObservation() {
      return matchObservation.get();
    }

    TransactionObservation encodeObservation() {
      return encodeObservation.get();
    }

    void pauseNextTwoEncodes() {
      encodeGate.set(new EncodeGate(new CountDownLatch(2), new CountDownLatch(1)));
    }

    void awaitBothPasswordChangesHashed() throws InterruptedException {
      assertThat(currentEncodeGate().encoded().await(10, TimeUnit.SECONDS))
          .as("both password changes should hash before either completion starts")
          .isTrue();
    }

    void releasePasswordChanges() {
      var gate = encodeGate.getAndSet(null);
      if (gate != null) {
        gate.continueChanges().countDown();
      }
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

    void reset() {
      releaseLogin();
      releasePasswordChanges();
      observePasswordChange.set(false);
      matchObservation.set(null);
      encodeObservation.set(null);
    }

    @Override
    public String encode(CharSequence rawPassword) {
      var encoded = delegate.encode(rawPassword);
      if (observePasswordChange.compareAndSet(true, false)) {
        encodeObservation.set(observeTransaction());
      }
      pauseEncodedPasswordChange();
      return encoded;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      var matches = delegate.matches(rawPassword, encodedPassword);
      if (observePasswordChange.get()) {
        matchObservation.set(observeTransaction());
      }
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

    private EncodeGate currentEncodeGate() {
      var gate = encodeGate.get();
      if (gate == null) {
        throw new AssertionError("no password-change encode gate has been prepared");
      }
      return gate;
    }

    private TransactionObservation observeTransaction() {
      return new TransactionObservation(
          TransactionSynchronizationManager.isActualTransactionActive(),
          TransactionSynchronizationManager.hasResource(dataSource));
    }

    private void pauseEncodedPasswordChange() {
      var gate = encodeGate.get();
      if (gate == null) {
        return;
      }
      gate.encoded().countDown();
      try {
        if (!gate.continueChanges().await(10, TimeUnit.SECONDS)) {
          throw new AssertionError("password-change encode gate was never released");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("password-change encode gate was interrupted", exception);
      }
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

    private record EncodeGate(CountDownLatch encoded, CountDownLatch continueChanges) {}
  }

  private record TransactionObservation(boolean transactionActive, boolean connectionBound) {

    private static final TransactionObservation NONE = new TransactionObservation(false, false);
  }
}
