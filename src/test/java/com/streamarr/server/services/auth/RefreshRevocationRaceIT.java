package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupportConfig;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Refresh Revocation Race Integration Tests")
@Import(AuthTestSupportConfig.class)
class RefreshRevocationRaceIT extends AbstractIntegrationTest {

  private static final int ROUNDS = 25;
  private static final int REFRESH_ROTATION_GATE_KEY = 316060;

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private AuthSessionRepository authSessionRepository;

  @Autowired private RefreshTokenRepository refreshTokenRepository;

  @Autowired private DeviceRegistrationRepository deviceRegistrationRepository;

  @Autowired private DeviceRegistrationLifecycle registrationLifecycle;

  @Autowired private DataSource dataSource;

  @Autowired private DSLContext dsl;

  @Autowired private PlatformTransactionManager transactionManager;

  private UserAccount account;

  @AfterEach
  void deleteAccountAndCascades() {
    deviceRegistrationRepository.deleteAll();
    if (account != null) {
      authTestSupport.deleteAccount(account.getId());
    }
  }

  @Test
  @DisplayName("Should leave no active token on session when refresh races revocation")
  void shouldLeaveNoActiveTokenOnSessionWhenRefreshRacesRevocation() {
    account = authTestSupport.createAccount();

    for (int round = 0; round < ROUNDS; round++) {
      var issued = refreshTokenService.createSession(account, "race-device");
      var sessionId = issued.session().getId();

      raceRefreshAgainstRevocation(issued.rawToken(), sessionId);

      // The session is revoked and its family fully swept: no ACTIVE token survives the race, so
      // the revoked session can never mint another access token.
      var session = authSessionRepository.findById(sessionId).orElseThrow();
      assertThat(session.getRevokedAt()).isNotNull();
      assertThat(activeTokenCountFor(sessionId)).isZero();
    }
  }

  @Test
  @DisplayName("Should reject redeeming a successor when minted before revocation")
  void shouldRejectRedeemingSuccessorWhenMintedBeforeRevocation() {
    account = authTestSupport.createAccount();
    var issued = refreshTokenService.createSession(account, "sequential-device");

    var rotated = (RefreshResult.Rotated) refreshTokenService.redeem(issued.rawToken());
    revokeAndSweep(issued.session().getId());

    // A successor handed out just before revocation must not keep the family alive.
    var successorToken = rotated.rawRefreshToken();
    assertThatThrownBy(() -> refreshTokenService.redeem(successorToken))
        .isInstanceOf(TokenReuseDetectedException.class);
    assertThat(activeTokenCountFor(issued.session().getId())).isZero();
  }

  @Test
  @DisplayName("Should reject a successor when registration revocation waits for its refresh")
  void shouldRejectSuccessorWhenRegistrationRevocationWaitsForRefresh() throws Exception {
    account = authTestSupport.createAccount();
    var registration =
        deviceRegistrationRepository.saveAndFlush(
            DeviceRegistration.builder()
                .esn("esn-refresh-race")
                .displayName("TV")
                .householdId(account.getHouseholdId())
                .authorizingAccountId(account.getId())
                .build());
    var issued =
        refreshTokenService.createSession(
            CreateAuthSessionCommand.builder()
                .accountId(account.getId())
                .deviceName("TV")
                .contextHouseholdId(account.getHouseholdId())
                .registrationId(Optional.of(registration.getId()))
                .build());
    var gate = holdRefreshRotation();

    RefreshResult.Rotated rotated;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        gate) {
      var refresh = executor.submit(() -> refreshTokenService.redeem(issued.rawToken()));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(advisoryLockWaiterCount()).isEqualTo(1));
      var revocation =
          executor.submit(
              () -> {
                revokeRegistration(registration.getId());
                return null;
              });
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(transactionLockWaiterCount()).isGreaterThanOrEqualTo(1));
      releaseRefreshRotation(gate);
      rotated = (RefreshResult.Rotated) refresh.get(20, TimeUnit.SECONDS);
      revocation.get(20, TimeUnit.SECONDS);
    } finally {
      removeRefreshRotationGate();
    }

    assertThat(
            deviceRegistrationRepository.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(
            authSessionRepository.findById(issued.session().getId()).orElseThrow().getRevokedAt())
        .isNotNull();
    var successorToken = rotated.rawRefreshToken();
    assertThatThrownBy(() -> refreshTokenService.redeem(successorToken))
        .isInstanceOf(TokenReuseDetectedException.class);
  }

  private void raceRefreshAgainstRevocation(String rawToken, UUID sessionId) {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var startLatch = new CountDownLatch(1);
      var doneLatch = new CountDownLatch(2);
      var errors = new CopyOnWriteArrayList<Throwable>();

      executor.submit(
          guarded(startLatch, doneLatch, errors, () -> refreshTokenService.redeem(rawToken)));
      executor.submit(guarded(startLatch, doneLatch, errors, () -> revokeAndSweep(sessionId)));

      startLatch.countDown();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(doneLatch.getCount()).isZero());

      // Refresh may legitimately reject (it lost the race); nothing should fail unexpectedly —
      // no deadlock, and revocation never fails.
      assertThat(errors).isEmpty();
    }
  }

  /** Mirrors the atomic revoke-and-sweep that logout and password-change perform downstream. */
  private void revokeAndSweep(UUID sessionId) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              authSessionRepository.revoke(
                  sessionId, SessionRevocationReason.LOGOUT, Instant.now());
              refreshTokenRepository.revokeAllForSession(sessionId, Instant.now());
            });
  }

  private void revokeRegistration(UUID registrationId) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ ->
                registrationLifecycle.revoke(
                    registrationId, account.getId(), "removed", Instant.now()));
  }

  private Connection holdRefreshRotation() throws SQLException {
    dsl.execute(
        """
        CREATE FUNCTION gate_refresh_rotation()
            RETURNS TRIGGER
            LANGUAGE plpgsql
        AS
        $$
        BEGIN
            IF OLD.status = 'ACTIVE' AND NEW.status = 'ROTATED' THEN
                PERFORM pg_advisory_xact_lock(316060);
            END IF;
            RETURN NEW;
        END;
        $$
        """);
    dsl.execute(
        """
        CREATE TRIGGER trg_gate_refresh_rotation
            BEFORE UPDATE ON refresh_token
            FOR EACH ROW EXECUTE FUNCTION gate_refresh_rotation()
        """);
    var connection = dataSource.getConnection();
    try (var statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
      statement.setInt(1, REFRESH_ROTATION_GATE_KEY);
      statement.execute();
    }

    return connection;
  }

  private void releaseRefreshRotation(Connection connection) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      statement.setInt(1, REFRESH_ROTATION_GATE_KEY);
      statement.execute();
    }
  }

  private int advisoryLockWaiterCount() {
    return lockWaiterCount("advisory");
  }

  private int transactionLockWaiterCount() {
    return lockWaiterCount("transactionid");
  }

  private int lockWaiterCount(String waitEvent) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from("pg_stat_activity")
            .where(DSL.field("wait_event", String.class).eq(waitEvent)));
  }

  private void removeRefreshRotationGate() {
    dsl.execute("DROP TRIGGER IF EXISTS trg_gate_refresh_rotation ON refresh_token");
    dsl.execute("DROP FUNCTION IF EXISTS gate_refresh_rotation()");
  }

  private Runnable guarded(
      CountDownLatch start,
      CountDownLatch done,
      CopyOnWriteArrayList<Throwable> errors,
      Runnable body) {
    return () -> {
      try {
        start.await();
        body.run();
      } catch (TokenReuseDetectedException | InvalidRefreshTokenException _) {
        // The refresh lost the race to revocation — expected, not an error.
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException e) {
        errors.add(e);
      } finally {
        done.countDown();
      }
    };
  }

  private long activeTokenCountFor(UUID sessionId) {
    return refreshTokenRepository.findAll().stream()
        .filter(token -> sessionId.equals(token.getSessionId()))
        .filter(token -> token.getStatus() == RefreshTokenStatus.ACTIVE)
        .count();
  }
}
