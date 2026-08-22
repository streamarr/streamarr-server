package com.streamarr.server.services.auth;

import static com.streamarr.server.jooq.generated.Tables.DEVICE_REGISTRATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.DeviceAuthorization;
import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.DeviceCodeNotPendingException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.EsnBlockRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupportConfig;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.sql.DataSource;
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

@Tag("IntegrationTest")
@DisplayName("Device Redemption Concurrency Integration Tests")
@Import(AuthTestSupportConfig.class)
class DeviceRedemptionConcurrencyIT extends AbstractIntegrationTest {

  private static final int POLLERS = 8;
  private static final int ESN_LOCK_NAMESPACE = 0x5354524D;
  private static final int REGISTRATION_INSERT_GATE_KEY = 316059;

  @Autowired private DeviceAuthorizationService deviceAuthorizationService;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private DeviceAuthorizationRepository authorizationRepository;

  @Autowired private DeviceRegistrationRepository registrationRepository;

  @Autowired private EsnBlockRepository esnBlockRepository;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private AuthSessionRepository sessionRepository;

  @Autowired private DataSource dataSource;

  @Autowired private DSLContext dsl;

  @Autowired private GatedAccessTokenIssuer gatedIssuer;

  @Autowired private GatedClock gatedClock;

  private final List<UUID> accountIds = new ArrayList<>();

  @AfterEach
  void deleteSeededRows() {
    gatedIssuer.reset();
    gatedClock.reset();
    authorizationRepository.deleteAll();
    registrationRepository.deleteAll();
    esnBlockRepository.deleteAll();
    for (var accountId : accountIds) {
      // FK cascades sweep auth_session and refresh_token rows.
      authTestSupport.deleteAccount(accountId);
    }
    accountIds.clear();
  }

  @TestConfiguration
  static class GatedIssuerConfig {

    @Bean
    @Primary
    GatedClock gatedClock() {
      return new GatedClock(Clock.systemUTC());
    }

    @Bean
    @Primary
    GatedAccessTokenIssuer gatedAccessTokenIssuer(
        JwtEncoder jwtEncoder, AuthTokenProperties properties, Clock clock) {
      return new GatedAccessTokenIssuer(jwtEncoder, properties, clock);
    }
  }

  static class GatedClock extends Clock {

    // decide() reads the row, takes one clock instant, then writes: park on that single call.
    private static final int APPROVAL_CLOCK_CALLS_BEFORE_WRITE = 1;

    private final Clock delegate;
    private final AtomicReference<Thread> gatedThread = new AtomicReference<>();
    private final AtomicInteger callsUntilHold = new AtomicInteger();
    private final AtomicReference<CountDownLatch> reachedHold = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> releaseHold = new AtomicReference<>();

    GatedClock(Clock delegate) {
      this.delegate = delegate;
    }

    void prepareApprovalHold() {
      callsUntilHold.set(APPROVAL_CLOCK_CALLS_BEFORE_WRITE);
      reachedHold.set(new CountDownLatch(1));
      releaseHold.set(new CountDownLatch(1));
    }

    void gateCurrentThread() {
      gatedThread.set(Thread.currentThread());
    }

    boolean awaitApprovalBeforeWrite() throws InterruptedException {
      return reachedHold.get().await(10, TimeUnit.SECONDS);
    }

    void releaseApproval() {
      var release = releaseHold.get();
      if (release != null) {
        release.countDown();
      }
    }

    void reset() {
      releaseApproval();
      gatedThread.set(null);
      callsUntilHold.set(0);
      reachedHold.set(null);
      releaseHold.set(null);
    }

    @Override
    public ZoneId getZone() {
      return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new GatedClock(delegate.withZone(zone));
    }

    @Override
    public Instant instant() {
      if (Thread.currentThread() == gatedThread.get() && callsUntilHold.decrementAndGet() == 0) {
        reachedHold.get().countDown();
        awaitRelease();
      }
      return delegate.instant();
    }

    private void awaitRelease() {
      try {
        if (!releaseHold.get().await(20, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Approval gate was never released.");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while approval was gated.", e);
      }
    }
  }

  static class GatedAccessTokenIssuer extends AccessTokenIssuer {

    private final AtomicBoolean failNextIssue = new AtomicBoolean();

    GatedAccessTokenIssuer(JwtEncoder jwtEncoder, AuthTokenProperties properties, Clock clock) {
      super(jwtEncoder, properties, clock);
    }

    @Override
    public AccessToken issue(TokenContext context) {
      if (failNextIssue.getAndSet(false)) {
        throw new IllegalStateException("Injected issuance failure");
      }
      return super.issue(context);
    }

    void failNextIssuance() {
      failNextIssue.set(true);
    }

    void reset() {
      failNextIssue.set(false);
    }
  }

  @Test
  @DisplayName("Should create exactly one session when many pollers race an approved grant")
  void shouldCreateExactlyOneSessionWhenManyPollersRaceApprovedGrant() throws Exception {
    var approver = seedApprover();
    var issued = deviceAuthorizationService.issue("Apple TV", "esn-1");
    approve(issued.userCode(), approver);

    var results = pollConcurrently(issued.deviceCode(), POLLERS);

    // The row lock serializes every poller: one wins the grant, the rest see a consumed row.
    assertThat(results).filteredOn(DevicePollResult.Success.class::isInstance).hasSize(1);
    assertThat(results).filteredOn(DevicePollResult.Expired.class::isInstance).hasSize(POLLERS - 1);
    assertThat(sessionsOf(approver)).hasSize(1);
    assertThat(statusOf(issued.userCode())).isEqualTo(DeviceAuthorizationStatus.CONSUMED);
  }

  @Test
  @DisplayName("Should serialize redemptions when approved grants share an ESN")
  void shouldSerializeRedemptionsWhenApprovedGrantsShareEsn() throws Exception {
    var approver = seedApprover();
    var first = deviceAuthorizationService.issue("First TV", "shared-esn");
    var second = deviceAuthorizationService.issue("Second TV", "shared-esn");
    approve(first.userCode(), approver);
    approve(second.userCode(), approver);
    var gate = holdRegistrationInserts();

    List<DevicePollResult> results = new ArrayList<>();
    var failures = new ArrayList<Throwable>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        gate) {
      var futures =
          List.of(
              executor.submit(() -> deviceAuthorizationService.redeem(first.deviceCode())),
              executor.submit(() -> deviceAuthorizationService.redeem(second.deviceCode())));
      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(advisoryLockWaiterCount()).isEqualTo(2));
      releaseRegistrationInserts(gate);
      for (var future : futures) {
        try {
          results.add(future.get(20, TimeUnit.SECONDS));
        } catch (ExecutionException failure) {
          failures.add(failure.getCause());
        }
      }
    } finally {
      removeRegistrationInsertGate();
    }

    assertThat(failures).isEmpty();
    assertThat(results).hasSize(2).allMatch(DevicePollResult.Success.class::isInstance);
    assertThat(registrationRepository.findAll())
        .filteredOn(registration -> registration.getStatus() == DeviceRegistrationStatus.ACTIVE)
        .hasSize(1);
    assertThat(sessionsOf(approver))
        .filteredOn(session -> session.getRevokedAt() == null)
        .hasSize(1);
  }

  @Test
  @DisplayName("Should expire a winning poll when an ESN block commits before registration")
  void shouldExpireWinningPollWhenEsnBlockCommitsBeforeRegistration() throws Exception {
    var approver = seedApprover();
    var issued = deviceAuthorizationService.issue("Apple TV", "blocked-during-poll");
    approve(issued.userCode(), approver);
    var esnLock = holdEsnLock("blocked-during-poll");

    DevicePollResult result;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        esnLock) {
      var poll = executor.submit(() -> deviceAuthorizationService.redeem(issued.deviceCode()));
      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(advisoryLockWaiterCount()).isEqualTo(1));
      esnBlockRepository.saveAndFlush(
          EsnBlock.builder().esn("blocked-during-poll").reason("stolen").build());
      releaseEsnLock(esnLock, "blocked-during-poll");
      result = poll.get(20, TimeUnit.SECONDS);
    }

    assertThat(result).isInstanceOf(DevicePollResult.Expired.class);
    assertThat(
            dsl.fetchCount(DEVICE_REGISTRATION, DEVICE_REGISTRATION.ESN.eq("blocked-during-poll")))
        .isZero();
    assertThat(sessionsOf(approver)).isEmpty();
  }

  @Test
  @DisplayName("Should return pending when a poll locks the row before approval commits")
  void shouldReturnPendingWhenPollLocksRowBeforeApprovalCommits() throws Exception {
    var approver = seedApprover();
    var issued = deviceAuthorizationService.issue("Apple TV", "esn-1");
    gatedClock.prepareApprovalHold();

    DevicePollResult result;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var approval =
          executor.submit(
              () -> {
                gatedClock.gateCurrentThread();
                approve(issued.userCode(), approver);
                return null;
              });

      assertThat(gatedClock.awaitApprovalBeforeWrite()).isTrue();
      try {
        assertThat(statusOf(issued.userCode())).isEqualTo(DeviceAuthorizationStatus.PENDING);
        result = deviceAuthorizationService.redeem(issued.deviceCode());
      } finally {
        gatedClock.releaseApproval();
      }
      approval.get(20, TimeUnit.SECONDS);
    }

    assertThat(result).isInstanceOf(DevicePollResult.Pending.class);
    assertThat(statusOf(issued.userCode())).isEqualTo(DeviceAuthorizationStatus.APPROVED);
  }

  @Test
  @DisplayName("Should preserve the winning decision when two approvers race")
  void shouldPreserveWinningDecisionWhenTwoApproversRace() throws Exception {
    var losingApprover = seedApprover();
    var winningApprover = seedApprover();
    var issued = deviceAuthorizationService.issue("Apple TV", "esn-1");
    gatedClock.prepareApprovalHold();

    DeviceAuthorizationDetails winningDecision;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var losingDecision =
          executor.submit(
              () -> {
                gatedClock.gateCurrentThread();
                return decide(issued.userCode(), DeviceDecision.APPROVE, losingApprover);
              });

      assertThat(gatedClock.awaitApprovalBeforeWrite()).isTrue();
      try {
        winningDecision = decide(issued.userCode(), DeviceDecision.DENY, winningApprover);
      } finally {
        gatedClock.releaseApproval();
      }

      assertThatThrownBy(() -> losingDecision.get(20, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(DeviceCodeNotPendingException.class);
    }

    assertThat(winningDecision.status()).isEqualTo(DeviceAuthorizationStatus.DENIED);
    assertThat(authorizationOf(issued.userCode()))
        .satisfies(
            authorization -> {
              assertThat(authorization.getStatus()).isEqualTo(DeviceAuthorizationStatus.DENIED);
              assertThat(authorization.getDecidedByAccountId()).isEqualTo(winningApprover.getId());
            });
  }

  @Test
  @DisplayName("Should refuse an approved grant when its approver has been deleted")
  void shouldRefuseApprovedGrantWhenApproverDeleted() {
    var approver = seedApprover();
    var issued = deviceAuthorizationService.issue("Apple TV", "esn-1");
    approve(issued.userCode(), approver);

    // A deleted approver no longer authorizes consumption. No write is attempted, so this is a
    // refusal path rather than transaction-rollback evidence.
    authTestSupport.deleteAccount(approver.getId());
    accountIds.remove(approver.getId());

    assertThat(deviceAuthorizationService.redeem(issued.deviceCode()))
        .isInstanceOf(DevicePollResult.Expired.class);
    assertThat(statusOf(issued.userCode())).isEqualTo(DeviceAuthorizationStatus.APPROVED);
  }

  @Test
  @DisplayName("Should roll back session creation when access-token issuance fails")
  void shouldRollBackSessionCreationWhenAccessTokenIssuanceFails() {
    var approver = seedApprover();
    var issued = deviceAuthorizationService.issue("Apple TV", "esn-1");
    var deviceCode = issued.deviceCode();
    approve(issued.userCode(), approver);
    gatedIssuer.failNextIssuance();

    assertThatThrownBy(() -> deviceAuthorizationService.redeem(deviceCode))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Injected issuance failure");

    assertThat(sessionsOf(approver)).isEmpty();
    assertThat(statusOf(issued.userCode())).isEqualTo(DeviceAuthorizationStatus.APPROVED);
    assertThat(deviceAuthorizationService.redeem(deviceCode))
        .isInstanceOf(DevicePollResult.Success.class);
    assertThat(sessionsOf(approver)).hasSize(1);
  }

  @Test
  @DisplayName("Should advance the cadence exactly once per poll when polls arrive concurrently")
  void shouldAdvanceCadenceExactlyOncePerPollWhenPollsArriveConcurrently() throws Exception {
    seedApprover();
    var issued = deviceAuthorizationService.issue("Apple TV", "esn-1");

    var results = pollConcurrently(issued.deviceCode(), 4);

    // Size first: a poller whose exception was swallowed would shorten the list, and a filtered
    // assertion over an empty list passes vacuously. Whichever poller takes the row lock first is
    // due at issuance; the other three land inside the interval it starts and each pays the
    // cumulative five-second penalty exactly once.
    assertThat(results).hasSize(4);
    assertThat(results).filteredOn(DevicePollResult.Pending.class::isInstance).hasSize(1);
    assertThat(results).filteredOn(DevicePollResult.SlowDown.class::isInstance).hasSize(3);
    assertThat(intervalOf(issued.userCode())).isEqualTo(5 + 3 * 5);
  }

  private List<DevicePollResult> pollConcurrently(String deviceCode, int pollers) throws Exception {
    var start = new CyclicBarrier(pollers);
    var attempts =
        IntStream.range(0, pollers)
            .mapToObj(
                _ ->
                    (Callable<DevicePollResult>)
                        () -> {
                          start.await(20, TimeUnit.SECONDS);
                          return deviceAuthorizationService.redeem(deviceCode);
                        })
            .toList();
    var results = new ArrayList<DevicePollResult>();
    var failures = new ArrayList<Throwable>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = executor.invokeAll(attempts, 20, TimeUnit.SECONDS);
      assertThat(futures).noneMatch(Future::isCancelled);
      for (var future : futures) {
        try {
          results.add(future.get());
        } catch (ExecutionException failure) {
          failures.add(failure.getCause());
        }
      }
    }

    assertThat(failures).isEmpty();
    return List.copyOf(results);
  }

  private Connection holdRegistrationInserts() throws SQLException {
    dsl.execute(
        """
        CREATE FUNCTION gate_device_registration_insert()
            RETURNS TRIGGER
            LANGUAGE plpgsql
        AS
        $$
        BEGIN
            PERFORM pg_advisory_xact_lock(316059);
            RETURN NEW;
        END;
        $$
        """);
    dsl.execute(
        """
        CREATE TRIGGER trg_gate_device_registration_insert
            BEFORE INSERT ON device_registration
            FOR EACH ROW EXECUTE FUNCTION gate_device_registration_insert()
        """);
    var connection = dataSource.getConnection();
    try (var statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
      statement.setInt(1, REGISTRATION_INSERT_GATE_KEY);
      statement.execute();
    }
    return connection;
  }

  private Connection holdEsnLock(String esn) throws SQLException {
    var connection = dataSource.getConnection();
    try (var statement = connection.prepareStatement("SELECT pg_advisory_lock(?, ?)")) {
      statement.setInt(1, ESN_LOCK_NAMESPACE);
      statement.setInt(2, esn.hashCode());
      statement.execute();
    }
    return connection;
  }

  private void releaseRegistrationInserts(Connection connection) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      statement.setInt(1, REGISTRATION_INSERT_GATE_KEY);
      statement.execute();
    }
  }

  private void releaseEsnLock(Connection connection, String esn) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
      statement.setInt(1, ESN_LOCK_NAMESPACE);
      statement.setInt(2, esn.hashCode());
      statement.execute();
    }
  }

  private int advisoryLockWaiterCount() {
    return dsl.fetchCount(
        dsl.selectOne()
            .from("pg_stat_activity")
            .where(DSL.field("wait_event", String.class).eq("advisory")));
  }

  private void removeRegistrationInsertGate() {
    dsl.execute(
        "DROP TRIGGER IF EXISTS trg_gate_device_registration_insert ON device_registration");
    dsl.execute("DROP FUNCTION IF EXISTS gate_device_registration_insert()");
  }

  private UserAccount seedApprover() {
    var approver = authTestSupport.createAccount();
    accountIds.add(approver.getId());
    return approver;
  }

  private void approve(String userCode, UserAccount approver) {
    decide(userCode, DeviceDecision.APPROVE, approver);
  }

  private DeviceAuthorizationDetails decide(
      String userCode, DeviceDecision decision, UserAccount approver) {
    return deviceAuthorizationService.decide(
        DeviceDecisionCommand.builder()
            .userCode(userCode)
            .decision(decision)
            .decidedByAccountId(approver.getId())
            .build());
  }

  private DeviceAuthorization authorizationOf(String displayUserCode) {
    return authorizationRepository
        .findByUserCode(UserCode.normalize(displayUserCode))
        .orElseThrow();
  }

  private DeviceAuthorizationStatus statusOf(String displayUserCode) {
    return authorizationOf(displayUserCode).getStatus();
  }

  private int intervalOf(String displayUserCode) {
    return authorizationRepository
        .findByUserCode(UserCode.normalize(displayUserCode))
        .orElseThrow()
        .getPollIntervalSeconds();
  }

  private List<AuthSession> sessionsOf(UserAccount approver) {
    return sessionRepository.findAll().stream()
        .filter(session -> approver.getId().equals(session.getAccountId()))
        .toList();
  }
}
