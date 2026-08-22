package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.RefreshToken.REFRESH_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.jooq.generated.enums.RefreshTokenStatus;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccessToken;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.auth.TokenContext;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupportConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtEncoder;

/**
 * Proves the refresh use case is one Spring-proxied transaction against real PostgreSQL: rotation,
 * account gating, context revalidation, and access-token issuance commit or roll back together. A
 * direct unit call cannot prove the lock lifetime or rollback behavior these tests pin.
 */
@Tag("IntegrationTest")
@DisplayName("Token Refresh Transaction Integration Tests")
@Import(AuthTestSupportConfig.class)
class TokenRefreshTransactionIT extends AbstractIntegrationTest {

  @Autowired private TokenRefreshService tokenRefreshService;

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private AuthSessionRepository authSessionRepository;

  @Autowired private GatedAccessTokenIssuer gatedIssuer;

  @Autowired private DSLContext dsl;

  @Autowired private AuthTestSupport authTestSupport;

  private AuthTestSupport.TestIdentity identity;
  private UserAccount account;

  @AfterEach
  void deleteIdentityAndCascades() {
    gatedIssuer.reset();
    if (identity != null) {
      // FK cascades sweep auth_session and refresh_token rows.
      authTestSupport.deleteIdentity(identity);
    }
  }

  private UserAccount createAccount() {
    identity = authTestSupport.createIdentity();
    return identity.account();
  }

  /**
   * Pauses or fails access-token issuance inside the live refresh transaction — the only way to
   * observe the session lock's lifetime and post-rotation rollback from outside.
   */
  @TestConfiguration
  static class GatedIssuerConfig {

    private final DSLContext dsl;

    GatedIssuerConfig(DSLContext dsl) {
      this.dsl = dsl;
    }

    @Bean
    @Primary
    GatedAccessTokenIssuer gatedAccessTokenIssuer(
        JwtEncoder jwtEncoder, AuthTokenProperties properties, Clock clock) {
      var issuer = new GatedAccessTokenIssuer(jwtEncoder, properties, clock);
      issuer.trackIssuingBackendWith(
          () -> dsl.select(DSL.function("pg_backend_pid", Integer.class)).fetchSingle().value1());
      return issuer;
    }
  }

  static class GatedAccessTokenIssuer extends AccessTokenIssuer {

    private final AtomicReference<CountDownLatch> holdGate = new AtomicReference<>();
    private final AtomicBoolean failNextIssue = new AtomicBoolean();
    private final AtomicReference<CountDownLatch> reachedIssue = new AtomicReference<>();
    private final AtomicReference<Integer> issuingBackendPid = new AtomicReference<>();
    private IntSupplier backendPidProbe;

    GatedAccessTokenIssuer(JwtEncoder jwtEncoder, AuthTokenProperties properties, Clock clock) {
      super(jwtEncoder, properties, clock);
    }

    @Override
    public AccessToken issue(TokenContext context) {
      var arrival = reachedIssue.get();
      if (arrival != null) {
        issuingBackendPid.set(backendPidProbe.getAsInt());
        arrival.countDown();
      }
      if (failNextIssue.getAndSet(false)) {
        throw new IllegalStateException("Injected issuance failure");
      }
      var gate = holdGate.get();
      if (gate != null) {
        awaitQuietly(gate);
      }
      return super.issue(context);
    }

    void trackIssuingBackendWith(IntSupplier backendPidProbe) {
      this.backendPidProbe = backendPidProbe;
    }

    int issuingBackendPid() {
      return issuingBackendPid.get();
    }

    void reset() {
      holdGate.set(null);
      reachedIssue.set(null);
      failNextIssue.set(false);
      issuingBackendPid.set(null);
    }

    void holdIssuanceAt(CountDownLatch gate, CountDownLatch arrival) {
      holdGate.set(gate);
      reachedIssue.set(arrival);
    }

    void failNextIssuance() {
      failNextIssue.set(true);
    }

    private static void awaitQuietly(CountDownLatch gate) {
      try {
        if (!gate.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Issue gate was never released.");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while gated.", e);
      }
    }
  }

  @Test
  @DisplayName("Should refuse refresh and keep rotation uncommitted when account disabled")
  void shouldRefuseRefreshAndKeepRotationUncommittedWhenAccountDisabled() {
    account = createAccount();
    var issued = refreshTokenService.createSession(account, "tx-device");
    account.setEnabled(false);
    account = userAccountRepository.save(account);
    var rawToken = issued.rawToken();

    assertThatThrownBy(() -> tokenRefreshService.refresh(rawToken))
        .isInstanceOf(InvalidRefreshTokenException.class);

    // The refusal must leave the client's token usable: rotation rolls back with the refused
    // transaction instead of stranding the family with a consumed predecessor.
    assertThat(activeTokenCount(issued.session().getId())).isEqualTo(1);
  }

  @Test
  @DisplayName("Should roll rotation back when issuance fails")
  void shouldRollRotationBackWhenIssuanceFails() {
    account = createAccount();
    var issued = refreshTokenService.createSession(account, "tx-device");
    gatedIssuer.failNextIssuance();
    var rawToken = issued.rawToken();

    assertThatThrownBy(() -> tokenRefreshService.refresh(rawToken))
        .isInstanceOf(IllegalStateException.class);

    // Rotation and issuance are one unit: the client's original token must remain redeemable.
    assertThat(activeTokenCount(issued.session().getId())).isEqualTo(1);
    assertThat(tokenRefreshService.refresh(issued.rawToken()).accessToken()).isNotNull();
  }

  @Test
  @DisplayName("Should refuse issuance when logout committed first")
  void shouldRefuseIssuanceWhenLogoutCommittedFirst() {
    account = createAccount();
    var issued = refreshTokenService.createSession(account, "tx-device");

    refreshTokenService.logout(issued.session().getId());
    var rawToken = issued.rawToken();

    assertThatThrownBy(() -> tokenRefreshService.refresh(rawToken))
        .isInstanceOf(TokenReuseDetectedException.class);
    assertThat(activeTokenCount(issued.session().getId())).isZero();
  }

  @Test
  @DisplayName("Should leave no active successor when logout completes after refresh")
  void shouldLeaveNoActiveSuccessorWhenLogoutCompletesAfterRefresh() {
    account = createAccount();
    var issued = refreshTokenService.createSession(account, "tx-device");

    var refreshed = tokenRefreshService.refresh(issued.rawToken());
    assertThat(refreshed.carriesRefreshToken()).isTrue();

    refreshTokenService.logout(issued.session().getId());

    assertThat(activeTokenCount(issued.session().getId())).isZero();
  }

  @Test
  @DisplayName("Should return access token only when recovered successor was superseded")
  void shouldReturnAccessTokenOnlyWhenRecoveredSuccessorWasSuperseded() {
    account = createAccount();
    var issued = refreshTokenService.createSession(account, "tx-device");
    var firstRotation = tokenRefreshService.refresh(issued.rawToken());
    tokenRefreshService.refresh(firstRotation.rawRefreshToken());

    var recovered = tokenRefreshService.refresh(issued.rawToken());

    assertThat(recovered.accessToken()).isNotNull();
    assertThat(recovered.carriesRefreshToken()).isFalse();
    assertThat(recovered.rawRefreshToken()).isNull();
  }

  @Test
  @DisplayName("Should hold session lock through issuance when logout races refresh")
  void shouldHoldSessionLockThroughIssuanceWhenLogoutRacesRefresh() throws Exception {
    account = createAccount();
    var issued = refreshTokenService.createSession(account, "tx-device");

    var gate = new CountDownLatch(1);
    var arrival = new CountDownLatch(1);
    gatedIssuer.holdIssuanceAt(gate, arrival);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var refreshDone = new CountDownLatch(1);
      var logoutDone = new CountDownLatch(1);
      var refreshResult = new AtomicReference<TokenRefreshService.RefreshedTokens>();

      executor.submit(
          () -> {
            try {
              refreshResult.set(tokenRefreshService.refresh(issued.rawToken()));
            } finally {
              refreshDone.countDown();
            }
          });

      assertThat(arrival.await(10, TimeUnit.SECONDS)).isTrue();

      executor.submit(
          () -> {
            try {
              refreshTokenService.logout(issued.session().getId());
            } finally {
              logoutDone.countDown();
            }
          });

      // The refresh transaction still holds the session lock, so logout must wait.
      assertThat(logoutDone.await(500, TimeUnit.MILLISECONDS)).isFalse();

      gate.countDown();

      assertThat(refreshDone.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(logoutDone.await(10, TimeUnit.SECONDS)).isTrue();

      // Refresh-first wins its issuance; the later logout still sweeps the whole family.
      assertThat(refreshResult.get()).isNotNull();
      assertThat(refreshResult.get().accessToken()).isNotNull();
      assertThat(activeTokenCount(issued.session().getId())).isZero();
    }
  }

  @Test
  @DisplayName("Should hold session lock through issuance when raw token logout races refresh")
  void shouldHoldSessionLockThroughIssuanceWhenRawTokenLogoutRacesRefresh() throws Exception {
    account = createAccount();
    var issued = refreshTokenService.createSession(account, "tx-device");

    var gate = new CountDownLatch(1);
    var arrival = new CountDownLatch(1);
    gatedIssuer.holdIssuanceAt(gate, arrival);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var refreshDone = new CountDownLatch(1);
      var logoutStarted = new CountDownLatch(1);
      var logoutDone = new CountDownLatch(1);
      var refreshResult = new AtomicReference<TokenRefreshService.RefreshedTokens>();

      executor.submit(
          () -> {
            try {
              refreshResult.set(tokenRefreshService.refresh(issued.rawToken()));
            } finally {
              refreshDone.countDown();
            }
          });

      assertThat(arrival.await(10, TimeUnit.SECONDS)).isTrue();

      executor.submit(
          () -> {
            logoutStarted.countDown();
            try {
              refreshTokenService.logout(issued.rawToken());
            } finally {
              logoutDone.countDown();
            }
          });

      assertThat(logoutStarted.await(10, TimeUnit.SECONDS)).isTrue();
      var refreshBackendPid = gatedIssuer.issuingBackendPid();
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                assertThat(blockedAuthSessionUpdateCount(refreshBackendPid)).isOne();
                assertThat(logoutDone.getCount()).isOne();
              });

      gate.countDown();

      assertThat(refreshDone.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(logoutDone.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(refreshResult.get()).isNotNull();
      assertThat(refreshResult.get().accessToken()).isNotNull();
      assertThat(activeTokenCount(issued.session().getId())).isZero();
    }
  }

  @Test
  @DisplayName("Should persist reuse revocation when redemption joins outer transaction")
  void shouldPersistReuseRevocationWhenRedemptionJoinsOuterTransaction() {
    account = createAccount();
    var issued = refreshTokenService.createSession(account, "tx-device");

    // Rotate once, then age the rotation past grace so replaying the original proves reuse.
    tokenRefreshService.refresh(issued.rawToken());
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.ROTATED_AT, OffsetDateTime.now().minus(Duration.ofMinutes(10)))
        .where(REFRESH_TOKEN.SESSION_ID.eq(issued.session().getId()))
        .and(REFRESH_TOKEN.STATUS.eq(RefreshTokenStatus.ROTATED))
        .execute();
    var rawToken = issued.rawToken();

    assertThatThrownBy(() -> tokenRefreshService.refresh(rawToken))
        .isInstanceOf(TokenReuseDetectedException.class);

    // afterCompletion runs before the proxied refresh call returns, so the revocation is already
    // observable without polling.
    var session = authSessionRepository.findById(issued.session().getId()).orElseThrow();
    assertThat(session.getRevokedAt()).isNotNull();
    assertThat(activeTokenCount(issued.session().getId())).isZero();
  }

  private int activeTokenCount(UUID sessionId) {
    return dsl.fetchCount(
        REFRESH_TOKEN,
        REFRESH_TOKEN
            .SESSION_ID
            .eq(sessionId)
            .and(REFRESH_TOKEN.STATUS.eq(RefreshTokenStatus.ACTIVE)));
  }

  private int blockedAuthSessionUpdateCount(int blockerPid) {
    var blockingPids =
        DSL.function("pg_blocking_pids", Integer[].class, DSL.field("pid", Integer.class));
    return dsl.fetchCount(
        dsl.selectOne()
            .from("pg_stat_activity")
            .where(DSL.arrayContains(blockingPids, DSL.val(blockerPid)))
            .and(DSL.field("wait_event_type", String.class).eq("Lock"))
            .and(DSL.field("query", String.class).containsIgnoreCase("auth_session"))
            .and(DSL.field("query", String.class).startsWithIgnoreCase("update")));
  }
}
