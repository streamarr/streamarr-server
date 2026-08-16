package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileDeletionAuthorization;
import com.streamarr.server.domain.auth.ProfileDeletionMode;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileDeletionAuthorizationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Profile Share Transition Race Integration Tests")
class ProfileShareTransitionRaceIT extends AbstractIntegrationTest {

  @Autowired private HouseholdRepository householdRepository;
  @Autowired private UserAccountRepository accountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository managerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileDeletionAuthorizationRepository deletionAuthorizationRepository;
  @Autowired private ProfileManagementService managementService;
  @Autowired private HouseholdProfileSafetyService safetyService;
  @Autowired private ProfileSelectionCleaner selectionCleaner;
  @Autowired private KidProfileManagerPolicy kidManagerPolicy;
  @Autowired private SecurityAuditService auditService;
  @Autowired private SecurityAuditEventRepository auditRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  @DisplayName("Should allow only acceptance or rejection of the same pending share")
  void shouldAllowOnlyAcceptanceOrRejectionOfSamePendingShare() throws Exception {
    var fixture = createFixture();
    var rejectionLoadedShare = new CountDownLatch(1);
    var acceptanceCompleted = new CountDownLatch(1);
    var service = serviceWithPausedFirstShareRead(rejectionLoadedShare, acceptanceCompleted);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var rejection =
          executor.submit(
              () ->
                  outcomeOf(
                      () ->
                          service.reject(
                              ProfileShareRejection.builder()
                                  .authority(targetAuthority(fixture))
                                  .shareId(fixture.shareId())
                                  .build())));

      await(rejectionLoadedShare);
      var acceptance =
          executor.submit(
              () ->
                  outcomeOf(
                      () ->
                          service.accept(
                              ProfileShareAcceptance.builder()
                                  .authority(targetAuthority(fixture))
                                  .shareId(fixture.shareId())
                                  .build())));

      var acceptanceOutcome = acceptance.get(30, TimeUnit.SECONDS);
      acceptanceCompleted.countDown();
      var rejectionOutcome = rejection.get(30, TimeUnit.SECONDS);

      assertThat(List.of(acceptanceOutcome, rejectionOutcome))
          .filteredOn(Outcome::succeeded)
          .hasSize(1);
      assertThat(shareRepository.findById(fixture.shareId()))
          .get()
          .extracting(ProfileHouseholdShare::getStatus)
          .isEqualTo(ProfileShareStatus.ACTIVE);
      assertThat(
              auditRepository.findByActingAccountIdAndOperation(
                  fixture.targetAccountId(), SecurityAuditOperation.PROFILE_SHARE_ACCEPTED))
          .hasSize(1);
      assertThat(
              auditRepository.findByActingAccountIdAndOperation(
                  fixture.targetAccountId(), SecurityAuditOperation.PROFILE_SHARE_REJECTED))
          .isEmpty();
    } finally {
      acceptanceCompleted.countDown();
      deleteFixture(fixture);
    }
  }

  @Test
  @DisplayName("Should allow only acceptance or cancellation of the same pending share")
  void shouldAllowOnlyAcceptanceOrCancellationOfSamePendingShare() throws Exception {
    var fixture = createFixture();
    var cancellationLoadedShare = new CountDownLatch(1);
    var acceptanceCompleted = new CountDownLatch(1);
    var service = serviceWithPausedFirstManagerCheck(cancellationLoadedShare, acceptanceCompleted);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var cancellation =
          executor.submit(
              () ->
                  outcomeOf(
                      () ->
                          service.cancel(
                              ProfileShareCancellation.builder()
                                  .actingAccountId(fixture.managerAccountId())
                                  .shareId(fixture.shareId())
                                  .build())));

      await(cancellationLoadedShare);
      var acceptance =
          executor.submit(
              () ->
                  outcomeOf(
                      () ->
                          service.accept(
                              ProfileShareAcceptance.builder()
                                  .authority(targetAuthority(fixture))
                                  .shareId(fixture.shareId())
                                  .build())));

      var acceptanceOutcome = acceptance.get(30, TimeUnit.SECONDS);
      acceptanceCompleted.countDown();
      var cancellationOutcome = cancellation.get(30, TimeUnit.SECONDS);

      assertThat(List.of(acceptanceOutcome, cancellationOutcome))
          .filteredOn(Outcome::succeeded)
          .hasSize(1);
      assertThat(shareRepository.findById(fixture.shareId()))
          .get()
          .extracting(ProfileHouseholdShare::getStatus)
          .isEqualTo(ProfileShareStatus.ACTIVE);
      assertThat(
              auditRepository.findByActingAccountIdAndOperation(
                  fixture.managerAccountId(), SecurityAuditOperation.PROFILE_SHARE_CANCELED))
          .isEmpty();
    } finally {
      acceptanceCompleted.countDown();
      deleteFixture(fixture);
    }
  }

  private ProfileSharingService serviceWithPausedFirstShareRead(
      CountDownLatch shareRead, CountDownLatch continueFirstRead) {
    var pauseFirstRead = new AtomicBoolean(true);
    var blockingShareRepository =
        (ProfileHouseholdShareRepository)
            Proxy.newProxyInstance(
                ProfileHouseholdShareRepository.class.getClassLoader(),
                new Class<?>[] {ProfileHouseholdShareRepository.class},
                (_, method, arguments) -> {
                  var result = invoke(shareRepository, method, arguments);
                  if (method.getName().equals("findById")
                      && pauseFirstRead.compareAndSet(true, false)) {
                    shareRead.countDown();
                    await(continueFirstRead);
                  }
                  return result;
                });
    return new ProfileSharingService(
        managerRepository,
        blockingShareRepository,
        profileRepository,
        managementService,
        safetyService,
        selectionCleaner,
        kidManagerPolicy,
        auditService);
  }

  private ProfileSharingService serviceWithPausedFirstManagerCheck(
      CountDownLatch managerChecked, CountDownLatch continueFirstCheck) {
    var pauseFirstCheck = new AtomicBoolean(true);
    var blockingManagerRepository =
        (ProfileManagerRepository)
            Proxy.newProxyInstance(
                ProfileManagerRepository.class.getClassLoader(),
                new Class<?>[] {ProfileManagerRepository.class},
                (_, method, arguments) -> {
                  var result = invoke(managerRepository, method, arguments);
                  if (method.getName().equals("existsByAccountIdAndProfileId")
                      && pauseFirstCheck.compareAndSet(true, false)) {
                    managerChecked.countDown();
                    await(continueFirstCheck);
                  }
                  return result;
                });
    return new ProfileSharingService(
        blockingManagerRepository,
        shareRepository,
        profileRepository,
        managementService,
        safetyService,
        selectionCleaner,
        kidManagerPolicy,
        auditService);
  }

  private Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException exception) {
      throw exception.getCause();
    }
  }

  private Fixture createFixture() {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var sourceHousehold =
                  householdRepository.save(Household.builder().name("Share Race Source").build());
              var targetHousehold =
                  householdRepository.save(Household.builder().name("Share Race Target").build());
              var manager =
                  accountRepository.save(account(sourceHousehold.getId(), HouseholdRole.OWNER));
              var target =
                  accountRepository.save(account(targetHousehold.getId(), HouseholdRole.OWNER));
              var profile =
                  profileRepository.save(
                      Profile.builder().name("Share Race Adult").kind(ProfileKind.ADULT).build());
              managerRepository.save(
                  ProfileManager.builder()
                      .accountId(manager.getId())
                      .profileId(profile.getId())
                      .build());
              var share =
                  shareRepository.save(
                      ProfileHouseholdShare.builder()
                          .profileId(profile.getId())
                          .householdId(targetHousehold.getId())
                          .status(ProfileShareStatus.PENDING)
                          .build());
              return new Fixture(
                  sourceHousehold.getId(),
                  targetHousehold.getId(),
                  manager.getId(),
                  target.getId(),
                  profile.getId(),
                  share.getId());
            });
  }

  private UserAccount account(UUID householdId, HouseholdRole role) {
    return UserAccount.builder()
        .email("share-race-" + UUID.randomUUID() + "@example.com")
        .displayName("Share Race Account")
        .passwordHash("encoded")
        .accountRole(role == HouseholdRole.OWNER ? AccountRole.ADMIN : AccountRole.USER)
        .homeHouseholdId(householdId)
        .householdRole(role)
        .build();
  }

  private AuthenticatedIdentity targetAuthority(Fixture fixture) {
    return AuthenticatedIdentity.builder()
        .accountId(fixture.targetAccountId())
        .role(AccountRole.ADMIN)
        .authSessionId(UUID.randomUUID())
        .scope(TokenScope.ACCOUNT)
        .householdId(fixture.targetHouseholdId())
        .householdRole(HouseholdRole.OWNER)
        .build();
  }

  private void deleteFixture(Fixture fixture) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              auditRepository.deleteAll(
                  auditRepository.findByActingAccountIdAndOperation(
                      fixture.targetAccountId(), SecurityAuditOperation.PROFILE_SHARE_ACCEPTED));
              auditRepository.deleteAll(
                  auditRepository.findByActingAccountIdAndOperation(
                      fixture.targetAccountId(), SecurityAuditOperation.PROFILE_SHARE_REJECTED));
              auditRepository.deleteAll(
                  auditRepository.findByActingAccountIdAndOperation(
                      fixture.managerAccountId(), SecurityAuditOperation.PROFILE_SHARE_CANCELED));
              shareRepository.findById(fixture.shareId()).ifPresent(shareRepository::delete);
              deletionAuthorizationRepository.save(
                  ProfileDeletionAuthorization.builder()
                      .profileId(fixture.profileId())
                      .actingAccountId(fixture.managerAccountId())
                      .mode(ProfileDeletionMode.FORCE)
                      .build());
              managerRepository.deleteAll(managerRepository.findByProfileId(fixture.profileId()));
              profileRepository.deleteById(fixture.profileId());
              accountRepository.deleteAllById(
                  List.of(fixture.managerAccountId(), fixture.targetAccountId()));
              householdRepository.deleteAllById(
                  List.of(fixture.sourceHouseholdId(), fixture.targetHouseholdId()));
            });
  }

  private Outcome outcomeOf(Runnable operation) {
    try {
      operation.run();
      return new Outcome(true);
    } catch (RuntimeException _) {
      return new Outcome(false);
    }
  }

  private void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Profile share transition race was interrupted", exception);
    }
  }

  private record Outcome(boolean succeeded) {}

  private record Fixture(
      UUID sourceHouseholdId,
      UUID targetHouseholdId,
      UUID managerAccountId,
      UUID targetAccountId,
      UUID profileId,
      UUID shareId) {}
}
