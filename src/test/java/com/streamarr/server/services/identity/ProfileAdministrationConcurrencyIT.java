package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
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
    var targetLocked = new CountDownLatch(1);
    var releaseTarget = new CountDownLatch(1);
    var targetLockerBackendPid = new AtomicInteger();
    var lockProbe = new PostgresLockProbe(entityManager, jdbcTemplate);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var targetLock =
          executor.submit(
              () ->
                  lockTargetProfile(
                      target.profile().getId(),
                      targetLocked,
                      releaseTarget,
                      targetLockerBackendPid,
                      lockProbe));
      assertThat(targetLocked.await(10, TimeUnit.SECONDS)).isTrue();

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
              () -> assertThat(hasWaitingProfileMutation(targetLockerBackendPid.get())).isTrue());

      revokeServerAdmin(actor);
      releaseTarget.countDown();

      var rejections =
          reset
              .get(10, TimeUnit.SECONDS)
              .fold(
                  _ -> List.<ProfileRejections.AdministrativelyResetProfilePin>of(),
                  rejected -> rejected);
      assertThat(rejections).singleElement().isInstanceOf(ProfileRejections.ProfileNotFound.class);
      targetLock.get(10, TimeUnit.SECONDS);
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
        profileAdministrationService
            .createProfile(
                authenticatedIdentity(owner),
                ProfileAdministrationService.CreateProfileCommand.builder()
                    .householdId(owner.household().getId())
                    .name("Shared Kid")
                    .kind(ProfileKind.KID)
                    .build())
            .fold(accepted -> accepted, rejected -> null);
    assertThat(profile).isNotNull();
    var share =
        transactionTemplate.execute(
            _ ->
                shareRepository.saveAndFlush(
                    ProfileHouseholdShare.builder()
                        .profileId(profile.getId())
                        .householdId(supervisor.household().getId())
                        .status(ProfileShareStatus.ACTIVE)
                        .build()));
    var decisionReached = new CountDownLatch(1);
    var releaseDecision = new CountDownLatch(1);
    var revocationStarted = new CountDownLatch(1);
    var revocationBackendPid = new AtomicInteger();
    var gateOnce = new AtomicBoolean();
    var lockProbe = new PostgresLockProbe(entityManager, jdbcTemplate);
    gateRenameDecision(decisionReached, releaseDecision, gateOnce);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var rename =
          executor.submit(
              () ->
                  profileAdministrationService.renameProfile(
                      authenticatedIdentity(supervisor), profile.getId(), "Renamed Kid"));
      assertThat(decisionReached.await(10, TimeUnit.SECONDS)).isTrue();

      var revoke =
          executor.submit(
              () -> {
                revokeSupervision(
                    share.getId(), revocationStarted, revocationBackendPid, lockProbe);
                return null;
              });
      assertThat(revocationStarted.await(10, TimeUnit.SECONDS)).isTrue();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> assertThat(isShareRevocationWaiting(revocationBackendPid.get())).isTrue());

      releaseDecision.countDown();
      assertThat(rename.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
      revoke.get(10, TimeUnit.SECONDS);
    } finally {
      releaseDecision.countDown();
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
        profileAdministrationService
            .createProfile(
                authenticatedIdentity(manager),
                ProfileAdministrationService.CreateProfileCommand.builder()
                    .householdId(manager.household().getId())
                    .name("Managed Profile")
                    .build())
            .fold(accepted -> accepted, rejected -> null);
    assertThat(profile).isNotNull();
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
    var decisionReached = new CountDownLatch(1);
    var releaseDecision = new CountDownLatch(1);
    var revocationStarted = new CountDownLatch(1);
    var revocationBackendPid = new AtomicInteger();
    var gateOnce = new AtomicBoolean();
    var lockProbe = new PostgresLockProbe(entityManager, jdbcTemplate);
    gateRenameDecision(decisionReached, releaseDecision, gateOnce);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var rename =
          executor.submit(
              () ->
                  profileAdministrationService.renameProfile(
                      authenticatedIdentity(manager), profile.getId(), "Still Managed"));
      assertThat(decisionReached.await(10, TimeUnit.SECONDS)).isTrue();

      var revoke =
          executor.submit(
              () -> {
                revokeManagement(
                    relationship.getId(), revocationStarted, revocationBackendPid, lockProbe);
                return null;
              });
      assertThat(revocationStarted.await(10, TimeUnit.SECONDS)).isTrue();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> assertThat(isManagementRevocationWaiting(revocationBackendPid.get())).isTrue());

      releaseDecision.countDown();
      assertThat(rename.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
      revoke.get(10, TimeUnit.SECONDS);
    } finally {
      releaseDecision.countDown();
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
    var decisionReached = new CountDownLatch(1);
    var releaseDecision = new CountDownLatch(1);
    var revocationStarted = new CountDownLatch(1);
    var revocationBackendPid = new AtomicInteger();
    var gateOnce = new AtomicBoolean();
    var lockProbe = new PostgresLockProbe(entityManager, jdbcTemplate);
    gateCreateDecision(decisionReached, releaseDecision, gateOnce);

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
      assertThat(decisionReached.await(10, TimeUnit.SECONDS)).isTrue();

      var revoke =
          executor.submit(
              () -> {
                demoteHouseholdAdmin(actor, revocationStarted, revocationBackendPid, lockProbe);
                return null;
              });
      assertThat(revocationStarted.await(10, TimeUnit.SECONDS)).isTrue();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(lockProbe.isUserAccountUpdateWaiting(revocationBackendPid.get()))
                      .isTrue());

      releaseDecision.countDown();
      var created = create.get(10, TimeUnit.SECONDS).fold(accepted -> accepted, rejected -> null);
      assertThat(created).isNotNull();
      revoke.get(10, TimeUnit.SECONDS);
    } finally {
      releaseDecision.countDown();
    }

    assertThat(
            profileRepository.findByHouseholdId(actor.household().getId()).stream()
                .map(profile -> profile.getName()))
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

  private void gateRenameDecision(
      CountDownLatch decisionReached, CountDownLatch releaseDecision, AtomicBoolean gateOnce) {
    var authorizationSpy =
        AopTestUtils.<AuthorizationService>getUltimateTargetObject(authorizationService);
    var defaultAnswer =
        mockingDetails(authorizationSpy).getMockCreationSettings().getDefaultAnswer();
    doAnswer(
            invocation -> {
              var decision = defaultAnswer.answer(invocation);
              if (invocation.getArgument(1) instanceof Intent.RenameProfile
                  && gateOnce.compareAndSet(false, true)) {
                decisionReached.countDown();
                awaitLatch(
                    releaseDecision,
                    "Supervision revocation did not observe the authorized rename transaction");
              }

              return decision;
            })
        .when(authorizationSpy)
        .decide(any(AuthenticatedIdentity.class), any(Intent.UnitIntent.class));
  }

  private void gateCreateDecision(
      CountDownLatch decisionReached, CountDownLatch releaseDecision, AtomicBoolean gateOnce) {
    var authorizationSpy =
        AopTestUtils.<AuthorizationService>getUltimateTargetObject(authorizationService);
    var defaultAnswer =
        mockingDetails(authorizationSpy).getMockCreationSettings().getDefaultAnswer();
    doAnswer(
            invocation -> {
              var decision = defaultAnswer.answer(invocation);
              if (invocation.getArgument(1) instanceof Intent.CreateProfile
                  && gateOnce.compareAndSet(false, true)) {
                decisionReached.countDown();
                awaitLatch(
                    releaseDecision,
                    "HouseholdAdmin revocation did not observe the authorized creation transaction");
              }

              return decision;
            })
        .when(authorizationSpy)
        .decide(any(AuthenticatedIdentity.class), any(Intent.UnitIntent.class));
  }

  private void revokeSupervision(
      UUID shareId, CountDownLatch started, AtomicInteger backendPid, PostgresLockProbe lockProbe) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          backendPid.set(lockProbe.currentBackendPid());
          started.countDown();
          shareRepository.deleteById(shareId);
          shareRepository.flush();
        });
  }

  private void revokeManagement(
      UUID relationshipId,
      CountDownLatch started,
      AtomicInteger backendPid,
      PostgresLockProbe lockProbe) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          backendPid.set(lockProbe.currentBackendPid());
          started.countDown();
          profileManagerRepository.deleteById(relationshipId);
          profileManagerRepository.flush();
        });
  }

  private void demoteHouseholdAdmin(
      AuthTestSupport.TestIdentity actor,
      CountDownLatch started,
      AtomicInteger backendPid,
      PostgresLockProbe lockProbe) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(actor.account().getId()).orElseThrow();
          backendPid.set(lockProbe.currentBackendPid());
          started.countDown();
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
      UUID profileId,
      CountDownLatch locked,
      CountDownLatch release,
      AtomicInteger backendPid,
      PostgresLockProbe lockProbe) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          backendPid.set(lockProbe.currentBackendPid());
          dsl.select(PROFILE.ID)
              .from(PROFILE)
              .where(PROFILE.ID.eq(profileId))
              .forUpdate()
              .fetchSingle();
          locked.countDown();
          awaitLatch(release, "Administrative PIN reset did not reach the target Profile");
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

  private static void awaitLatch(CountDownLatch latch, String failureMessage) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as(failureMessage).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while coordinating Profile administration", e);
    }
  }
}
