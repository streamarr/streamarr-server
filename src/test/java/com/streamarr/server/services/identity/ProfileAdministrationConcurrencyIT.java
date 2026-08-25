package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockProbe;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Builder;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Profile Administration Concurrency Integration Tests")
class ProfileAdministrationConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private ProfileAdministrationService profileAdministrationService;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @MockitoSpyBean private AuthorizationService authorizationService;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();

  @AfterEach
  void deleteFixtures() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should reject an administrative PIN reset when ServerAdmin authority is revoked before the write")
  void shouldRejectAdministrativePinResetWhenServerAdminAuthorityIsRevokedBeforeWrite()
      throws Exception {
    var actor = identity(authTestSupport.createAdminIdentity());
    identity(authTestSupport.createAdminIdentity());
    var target = identity(authTestSupport.createIdentity());
    var targetLock = revocationProbe();
    var releaseTarget = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var lock =
          executor.submit(
              () -> lockTargetProfile(target.profile().getId(), targetLock, releaseTarget));
      targetLock.awaitStarted();

      var reset =
          executor.submit(
              () ->
                  profileAdministrationService.administrativelyResetProfilePin(
                      authenticatedIdentity(actor),
                      target.profile().getId(),
                      "4242",
                      "account recovery"));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> assertThat(hasWaitingProfileMutation(targetLock.backendPid())).isTrue());

      revokeServerAdmin(actor);
      releaseTarget.countDown();

      var rejections =
          reset
              .get(10, TimeUnit.SECONDS)
              .fold(
                  _ -> List.<ProfileRejections.AdministrativelyResetProfilePin>of(),
                  rejected -> rejected);
      assertThat(rejections).singleElement().isInstanceOf(ProfileRejections.ProfileNotFound.class);
      lock.get(10, TimeUnit.SECONDS);
    } finally {
      releaseTarget.countDown();
    }

    assertThat(profileRepository.findById(target.profile().getId()).orElseThrow().getPinHash())
        .isNull();
  }

  @Test
  @DisplayName("Should finish an authorized rename before supervision is revoked concurrently")
  void shouldFinishAuthorizedRenameBeforeSupervisionIsRevokedConcurrently() throws Exception {
    var supervisor = identity(authTestSupport.createIdentity());
    var owner = identity(authTestSupport.createIdentity());
    var profile =
        accepted(
            profileAdministrationService.createProfile(
                authenticatedIdentity(owner),
                ProfileAdministrationService.CreateProfileCommand.builder()
                    .householdId(owner.household().getId())
                    .name("Shared Kid")
                    .kind(ProfileKind.KID)
                    .build()));
    var share =
        transactionTemplate.execute(
            _ ->
                shareRepository.saveAndFlush(
                    ProfileHouseholdShare.builder()
                        .profileId(profile.getId())
                        .householdId(supervisor.household().getId())
                        .status(ProfileShareStatus.ACTIVE)
                        .build()));
    var decision =
        gateDecision(
            Intent.RenameProfile.class,
            "Supervision revocation did not observe the authorized rename transaction");
    var revocation = revocationProbe();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var rename =
          executor.submit(
              () ->
                  profileAdministrationService.renameProfile(
                      authenticatedIdentity(supervisor), profile.getId(), "Renamed Kid"));
      decision.awaitReached();

      var revoke =
          executor.submit(
              () -> {
                revokeSupervision(share.getId(), revocation);
                return null;
              });
      revocation.awaitStarted();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> assertThat(isShareRevocationWaiting(revocation.backendPid())).isTrue());

      decision.release();
      assertThat(rename.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
      revoke.get(10, TimeUnit.SECONDS);
    } finally {
      decision.release();
    }

    assertThat(profileRepository.findById(profile.getId()).orElseThrow().getName())
        .isEqualTo("Renamed Kid");
    assertThat(shareRepository.findById(share.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should finish an authorized rename before management is revoked concurrently")
  void shouldFinishAuthorizedRenameBeforeManagementIsRevokedConcurrently() throws Exception {
    var manager = identity(authTestSupport.createIdentity());
    var homeAnchor = createEligibleAccountIn(manager.household().getId());
    var profile =
        accepted(
            profileAdministrationService.createProfile(
                authenticatedIdentity(manager),
                ProfileAdministrationService.CreateProfileCommand.builder()
                    .householdId(manager.household().getId())
                    .name("Managed Profile")
                    .build()));
    transactionTemplate.executeWithoutResult(
        _ ->
            profileManagerRepository.saveAndFlush(
                ProfileManager.builder()
                    .accountId(homeAnchor.getId())
                    .profileId(profile.getId())
                    .build()));
    var relationship =
        profileManagerRepository.findByProfileId(profile.getId()).stream()
            .filter(candidate -> candidate.getAccountId().equals(manager.account().getId()))
            .findFirst()
            .orElseThrow();
    var decision =
        gateDecision(
            Intent.RenameProfile.class,
            "Management revocation did not observe the authorized rename transaction");
    var revocation = revocationProbe();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var rename =
          executor.submit(
              () ->
                  profileAdministrationService.renameProfile(
                      authenticatedIdentity(manager), profile.getId(), "Still Managed"));
      decision.awaitReached();

      var revoke =
          executor.submit(
              () -> {
                revokeManagement(relationship.getId(), revocation);
                return null;
              });
      revocation.awaitStarted();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> assertThat(isManagementRevocationWaiting(revocation.backendPid())).isTrue());

      decision.release();
      assertThat(rename.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
      revoke.get(10, TimeUnit.SECONDS);
    } finally {
      decision.release();
    }

    assertThat(profileRepository.findById(profile.getId()).orElseThrow().getName())
        .isEqualTo("Still Managed");
    assertThat(profileManagerRepository.findById(relationship.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should finish authorized creation before HouseholdAdmin authority is revoked")
  void shouldFinishAuthorizedCreationBeforeHouseholdAdminAuthorityIsRevoked() throws Exception {
    var actor = identity(authTestSupport.createIdentity());
    var remainingAdmin = createEligibleAccountIn(actor.household().getId());
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(remainingAdmin.getId()).orElseThrow();
          account.setHouseholdRole(HouseholdRole.ADMIN);
          userAccountRepository.saveAndFlush(account);
        });
    var decision =
        gateDecision(
            Intent.CreateProfile.class,
            "HouseholdAdmin revocation did not observe the authorized creation transaction");
    var revocation = revocationProbe();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var create =
          executor.submit(
              () ->
                  profileAdministrationService.createProfile(
                      authenticatedIdentity(actor),
                      ProfileAdministrationService.CreateProfileCommand.builder()
                          .householdId(actor.household().getId())
                          .name("Concurrent Profile")
                          .build()));
      decision.awaitReached();

      var revoke =
          executor.submit(
              () -> {
                demoteHouseholdAdmin(actor, revocation);
                return null;
              });
      revocation.awaitStarted();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(
                          revocation
                              .lockProbe()
                              .isUserAccountUpdateWaiting(revocation.backendPid()))
                      .isTrue());

      decision.release();
      accepted(create.get(10, TimeUnit.SECONDS));
      revoke.get(10, TimeUnit.SECONDS);
    } finally {
      decision.release();
    }

    assertThat(
            profileRepository.findByHouseholdId(actor.household().getId()).stream()
                .map(Profile::getName))
        .contains("Concurrent Profile");
    assertThat(userAccountRepository.findById(actor.account().getId()).orElseThrow())
        .extracting(UserAccount::getHouseholdRole)
        .isEqualTo(HouseholdRole.MEMBER);
  }

  private UserAccount createEligibleAccountIn(UUID householdId) {
    return transactionTemplate.execute(
        _ -> {
          var personal =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(householdId)
                      .name("Home Anchor")
                      .build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(householdId)
                      .householdRole(HouseholdRole.MEMBER)
                      .personalProfileId(personal.getId())
                      .passwordHash("test-only-hash")
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(personal.getId())
                  .householdId(householdId)
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  private DecisionGate gateDecision(
      Class<? extends Intent.UnitIntent> intentType, String failureMessage) {
    var gate =
        DecisionGate.builder()
            .intentType(intentType)
            .failureMessage(failureMessage)
            .reached(new CountDownLatch(1))
            .releaseLatch(new CountDownLatch(1))
            .gateOnce(new AtomicBoolean())
            .build();
    var authorizationSpy =
        AopTestUtils.<AuthorizationService>getUltimateTargetObject(authorizationService);
    var defaultAnswer =
        mockingDetails(authorizationSpy).getMockCreationSettings().getDefaultAnswer();
    doAnswer(
            invocation -> {
              var decision = defaultAnswer.answer(invocation);
              if (gate.shouldBlock(invocation.getArgument(1))) {
                gate.block();
              }

              return decision;
            })
        .when(authorizationSpy)
        .decide(any(AuthenticatedIdentity.class), any(Intent.UnitIntent.class));
    return gate;
  }

  private RevocationProbe revocationProbe() {
    return RevocationProbe.builder()
        .started(new CountDownLatch(1))
        .backendPidHolder(new AtomicInteger())
        .lockProbe(new PostgresLockProbe(entityManager, jdbcTemplate))
        .build();
  }

  private void revokeSupervision(UUID shareId, RevocationProbe revocation) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          revocation.markStarted();
          shareRepository.deleteById(shareId);
          shareRepository.flush();
        });
  }

  private void revokeManagement(UUID relationshipId, RevocationProbe revocation) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          revocation.markStarted();
          profileManagerRepository.deleteById(relationshipId);
          profileManagerRepository.flush();
        });
  }

  private void demoteHouseholdAdmin(
      AuthTestSupport.TestIdentity actor, RevocationProbe revocation) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(actor.account().getId()).orElseThrow();
          revocation.markStarted();
          account.setHouseholdRole(HouseholdRole.MEMBER);
          userAccountRepository.saveAndFlush(account);
        });
  }

  private boolean isShareRevocationWaiting(int backendPid) {
    var waiting =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pg_stat_activity
              WHERE pid = ?
                AND wait_event_type = 'Lock'
                AND query ILIKE '%delete%profile_household_share%'
            )
            """,
            Boolean.class, backendPid);
    return Boolean.TRUE.equals(waiting);
  }

  private boolean isManagementRevocationWaiting(int backendPid) {
    var waiting =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pg_stat_activity
              WHERE pid = ?
                AND wait_event_type = 'Lock'
                AND query ILIKE '%delete%profile_manager%'
            )
            """,
            Boolean.class, backendPid);
    return Boolean.TRUE.equals(waiting);
  }

  private Void lockTargetProfile(
      UUID profileId, RevocationProbe targetLock, CountDownLatch releaseTarget) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          dsl.select(PROFILE.ID)
              .from(PROFILE)
              .where(PROFILE.ID.eq(profileId))
              .forUpdate()
              .fetchSingle();
          targetLock.markStarted();
          awaitLatch(releaseTarget, "Administrative PIN reset did not reach the target Profile");
        });
    return null;
  }

  private void revokeServerAdmin(AuthTestSupport.TestIdentity actor) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(actor.account().getId()).orElseThrow();
          account.setServerAdmin(false);
          userAccountRepository.saveAndFlush(account);
        });
  }

  private boolean hasWaitingProfileMutation(int blockerBackendPid) {
    var waiting =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pg_stat_activity
              WHERE ? = ANY(pg_blocking_pids(pid))
                AND wait_event_type = 'Lock'
                AND query ILIKE '%profile%'
            )
            """,
            Boolean.class, blockerBackendPid);
    return Boolean.TRUE.equals(waiting);
  }

  private AuthTestSupport.TestIdentity identity(AuthTestSupport.TestIdentity identity) {
    identities.add(identity);
    return identity;
  }

  private static AuthenticatedIdentity authenticatedIdentity(
      AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.builder()
        .accountId(identity.account().getId())
        .authSessionId(identity.session().getId())
        .scope(TokenScope.ACCOUNT)
        .householdId(identity.household().getId())
        .householdRole(identity.account().getHouseholdRole())
        .contextHouseholdId(identity.household().getId())
        .reauthenticatedAt(Optional.of(Instant.now()))
        .build();
  }

  private static <T, R> T accepted(Outcome<T, R> outcome) {
    return switch (outcome) {
      case Outcome.Accepted<T, R>(var result) -> result;
      case Outcome.Rejected<T, R>(var rejections) ->
          throw new AssertionError("Expected an accepted mutation but got: " + rejections);
    };
  }

  private static void awaitLatch(CountDownLatch latch, String failureMessage) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as(failureMessage).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while coordinating Profile administration", e);
    }
  }

  @Builder
  private record DecisionGate(
      Class<? extends Intent.UnitIntent> intentType,
      String failureMessage,
      CountDownLatch reached,
      CountDownLatch releaseLatch,
      AtomicBoolean gateOnce) {

    private boolean shouldBlock(Intent.UnitIntent intent) {
      return intentType.isInstance(intent) && gateOnce.compareAndSet(false, true);
    }

    private void block() {
      reached.countDown();
      awaitLatch(releaseLatch, failureMessage);
    }

    private void awaitReached() {
      awaitLatch(reached, "Authorization did not reach the expected decision");
    }

    private void release() {
      releaseLatch.countDown();
    }
  }

  @Builder
  private record RevocationProbe(
      CountDownLatch started, AtomicInteger backendPidHolder, PostgresLockProbe lockProbe) {

    private void markStarted() {
      backendPidHolder.set(lockProbe.currentBackendPid());
      started.countDown();
    }

    private void awaitStarted() {
      awaitLatch(started, "Authority revocation did not start");
    }

    private int backendPid() {
      return backendPidHolder.get();
    }
  }
}
