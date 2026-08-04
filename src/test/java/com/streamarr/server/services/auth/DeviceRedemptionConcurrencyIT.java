package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtEncoder;

@Tag("IntegrationTest")
@DisplayName("Device Redemption Concurrency Integration Tests")
class DeviceRedemptionConcurrencyIT extends AbstractIntegrationTest {

  private static final int POLLERS = 8;

  @Autowired private DeviceAuthorizationService deviceAuthorizationService;

  @Autowired private DeviceAuthorizationRepository authorizationRepository;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private AuthSessionRepository sessionRepository;

  @Autowired private GatedAccessTokenIssuer gatedIssuer;

  private UUID accountId;

  @AfterEach
  void deleteSeededRows() {
    gatedIssuer.reset();
    authorizationRepository.deleteAll();
    if (accountId != null) {
      // FK cascades sweep auth_session and refresh_token rows.
      userAccountRepository.deleteById(accountId);
      accountId = null;
    }
  }

  @TestConfiguration
  static class GatedIssuerConfig {

    @Bean
    @Primary
    GatedAccessTokenIssuer gatedAccessTokenIssuer(
        JwtEncoder jwtEncoder,
        AuthTokenProperties properties,
        Clock clock,
        HouseholdMembershipRepository membershipRepository,
        ProfileRepository profileRepository,
        AccountProfileRepository accountProfileRepository) {
      return new GatedAccessTokenIssuer(
          jwtEncoder,
          properties,
          clock,
          membershipRepository,
          profileRepository,
          accountProfileRepository);
    }
  }

  static class GatedAccessTokenIssuer extends AccessTokenIssuer {

    private final AtomicBoolean failNextIssue = new AtomicBoolean();

    GatedAccessTokenIssuer(
        JwtEncoder jwtEncoder,
        AuthTokenProperties properties,
        Clock clock,
        HouseholdMembershipRepository membershipRepository,
        ProfileRepository profileRepository,
        AccountProfileRepository accountProfileRepository) {
      super(
          jwtEncoder,
          properties,
          clock,
          membershipRepository,
          profileRepository,
          accountProfileRepository);
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
  void shouldCreateExactlyOneSessionWhenManyPollersRaceApprovedGrant() {
    var approver = seedApprover();
    var issued = deviceAuthorizationService.issue("Apple TV");
    approve(issued.userCode(), approver);

    var results = pollConcurrently(issued.deviceCode(), POLLERS);

    // The row lock serializes every poller: one wins the grant, the rest see a consumed row.
    assertThat(results).filteredOn(DevicePollResult.Success.class::isInstance).hasSize(1);
    assertThat(results).filteredOn(DevicePollResult.Expired.class::isInstance).hasSize(POLLERS - 1);
    assertThat(sessionsOf(approver)).hasSize(1);
    assertThat(statusOf(issued.userCode())).isEqualTo(DeviceAuthorizationStatus.CONSUMED);
  }

  @Test
  @DisplayName("Should never report a live approved grant as expired when approval races a poll")
  void shouldNeverReportLiveApprovedGrantAsExpiredWhenApprovalRacesPoll() {
    var approver = seedApprover();
    var issued = deviceAuthorizationService.issue("Apple TV");

    var executor = Executors.newFixedThreadPool(2);
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(2);
    var results = new CopyOnWriteArrayList<DevicePollResult>();

    executor.submit(
        () -> {
          awaitStart(startLatch);
          approve(issued.userCode(), approver);
          doneLatch.countDown();
        });
    executor.submit(
        () -> {
          awaitStart(startLatch);
          results.add(deviceAuthorizationService.redeem(issued.deviceCode()));
          doneLatch.countDown();
        });

    startLatch.countDown();
    awaitCompletion(doneLatch);
    executor.shutdown();

    // Either order is legitimate; what must never happen is a live grant classified as expired.
    assertThat(results)
        .singleElement()
        .isNotInstanceOfAny(DevicePollResult.Expired.class, DevicePollResult.Denied.class);
  }

  @Test
  @DisplayName("Should refuse an approved grant when its approver has been deleted")
  void shouldRefuseApprovedGrantWhenApproverDeleted() {
    var approver = seedApprover();
    var issued = deviceAuthorizationService.issue("Apple TV");
    approve(issued.userCode(), approver);

    // A deleted approver no longer authorizes consumption. No write is attempted, so this is a
    // refusal path rather than transaction-rollback evidence.
    userAccountRepository.deleteById(approver.getId());
    accountId = null;

    assertThat(deviceAuthorizationService.redeem(issued.deviceCode()))
        .isInstanceOf(DevicePollResult.Expired.class);
    assertThat(statusOf(issued.userCode())).isEqualTo(DeviceAuthorizationStatus.APPROVED);
  }

  @Test
  @DisplayName("Should roll back session creation when access-token issuance fails")
  void shouldRollBackSessionCreationWhenAccessTokenIssuanceFails() {
    var approver = seedApprover();
    var issued = deviceAuthorizationService.issue("Apple TV");
    approve(issued.userCode(), approver);
    gatedIssuer.failNextIssuance();

    assertThatThrownBy(() -> deviceAuthorizationService.redeem(issued.deviceCode()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Injected issuance failure");

    assertThat(sessionsOf(approver)).isEmpty();
    assertThat(statusOf(issued.userCode())).isEqualTo(DeviceAuthorizationStatus.APPROVED);
    assertThat(deviceAuthorizationService.redeem(issued.deviceCode()))
        .isInstanceOf(DevicePollResult.Success.class);
    assertThat(sessionsOf(approver)).hasSize(1);
  }

  @Test
  @DisplayName("Should advance the cadence exactly once per poll under concurrency")
  void shouldAdvanceCadenceExactlyOncePerPollUnderConcurrency() {
    seedApprover();
    var issued = deviceAuthorizationService.issue("Apple TV");

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

  private List<DevicePollResult> pollConcurrently(String deviceCode, int pollers) {
    var executor = Executors.newFixedThreadPool(pollers);
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(pollers);
    var results = new CopyOnWriteArrayList<DevicePollResult>();
    var failures = new CopyOnWriteArrayList<Exception>();

    for (var poller = 0; poller < pollers; poller++) {
      executor.submit(
          () -> {
            awaitStart(startLatch);
            try {
              results.add(deviceAuthorizationService.redeem(deviceCode));
            } catch (Exception caught) {
              // A submitted task's exception dies inside its Future. Captured here so a poller
              // that blew up fails the test loudly, instead of just shortening the result list
              // and letting a whole-collection assertion pass over what survived.
              failures.add(caught);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    awaitCompletion(doneLatch);
    executor.shutdown();

    assertThat(failures).isEmpty();
    return List.copyOf(results);
  }

  private UserAccount seedApprover() {
    var approver = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    accountId = approver.getId();
    return approver;
  }

  private void approve(String userCode, UserAccount approver) {
    deviceAuthorizationService.decide(
        DeviceDecisionCommand.builder()
            .userCode(userCode)
            .decision(DeviceDecision.APPROVE)
            .decidedByAccountId(approver.getId())
            .build());
  }

  private DeviceAuthorizationStatus statusOf(String displayUserCode) {
    return authorizationRepository
        .findByUserCode(UserCode.normalize(displayUserCode))
        .orElseThrow()
        .getStatus();
  }

  private int intervalOf(String displayUserCode) {
    return authorizationRepository
        .findByUserCode(UserCode.normalize(displayUserCode))
        .orElseThrow()
        .getPollIntervalSeconds();
  }

  private List<com.streamarr.server.domain.auth.AuthSession> sessionsOf(UserAccount approver) {
    return sessionRepository.findAll().stream()
        .filter(session -> approver.getId().equals(session.getAccountId()))
        .toList();
  }

  private static void awaitStart(CountDownLatch startLatch) {
    try {
      startLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void awaitCompletion(CountDownLatch doneLatch) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(doneLatch.getCount()).isZero());
  }
}
