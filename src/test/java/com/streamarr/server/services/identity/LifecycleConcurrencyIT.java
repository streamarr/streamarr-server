package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.identity.AccountLifecycleService.DeleteAccountCommand;
import com.streamarr.server.services.identity.AccountLifecycleService.ProfileDisposition;
import com.streamarr.server.services.identity.AccountLifecycleService.SourceAccess;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.identity.ProfileLifecycleService.TransferProfileCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Lifecycle Concurrency Integration Tests")
class LifecycleConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private AccountLifecycleService accountLifecycleService;
  @Autowired private ProfileLifecycleService profileLifecycleService;
  @Autowired private AuthTestSupport authTestSupport;
  @MockitoSpyBean private UserAccountRepository accountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @MockitoSpyBean private ProfileManagerRepository managerRepository;
  @MockitoSpyBean private AccountInvitationRepository invitationRepository;
  @MockitoSpyBean private DeviceRegistrationLifecycle registrationLifecycle;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("Should preserve a password change committed while an Account transfer is paused")
  void shouldPreservePasswordChangeCommittedWhileAccountTransferIsPaused() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var destination = authTestSupport.createIdentity();
    var mover = residentOf(source.household().getId(), HouseholdRole.MEMBER);
    try {
      var transferReached = new CountDownLatch(1);
      var releaseTransfer = new CountDownLatch(1);
      gateTransferWrite(
          TransferGate.builder()
              .mover(mover)
              .sourceHouseholdId(source.household().getId())
              .transferReached(transferReached)
              .releaseTransfer(releaseTransfer)
              .build());

      Outcome<?, ?> transfer;
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var pendingTransfer =
            executor.submit(
                () ->
                    accountLifecycleService.transferAccount(
                        authenticated(actor), transferTo(mover, destination)));
        assertThat(transferReached.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(accountRepository.trySetPasswordHash(mover.getId(), "concurrent-password"))
            .isTrue();
        releaseTransfer.countDown();
        transfer = pendingTransfer.get(20, TimeUnit.SECONDS);
      }

      assertThat(transfer).isInstanceOf(Outcome.Accepted.class);
      assertThat(accountRepository.findById(mover.getId()).orElseThrow().getPasswordHash())
          .isEqualTo("concurrent-password");
    } finally {
      authTestSupport.deleteIdentity(destination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should preserve a rename committed while an Account transfer is paused")
  void shouldPreserveRenameCommittedWhileAccountTransferIsPaused() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var destination = authTestSupport.createIdentity();
    var mover = residentOf(source.household().getId(), HouseholdRole.MEMBER);
    try {
      var transferReached = new CountDownLatch(1);
      var releaseTransfer = new CountDownLatch(1);
      gateTransferWrite(
          TransferGate.builder()
              .mover(mover)
              .sourceHouseholdId(source.household().getId())
              .transferReached(transferReached)
              .releaseTransfer(releaseTransfer)
              .build());

      Outcome<?, ?> transfer;
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var pendingTransfer =
            executor.submit(
                () ->
                    accountLifecycleService.transferAccount(
                        authenticated(actor), transferTo(mover, destination)));
        assertThat(transferReached.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(accountRepository.tryRename(mover.getId(), "Concurrent rename")).isTrue();
        releaseTransfer.countDown();
        transfer = pendingTransfer.get(20, TimeUnit.SECONDS);
      }

      assertThat(transfer).isInstanceOf(Outcome.Accepted.class);
      assertThat(accountRepository.findById(mover.getId()).orElseThrow().getDisplayName())
          .isEqualTo("Concurrent rename");
    } finally {
      authTestSupport.deleteIdentity(destination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should reject deletion when a concurrent transfer changes the Account Household")
  void shouldRejectDeletionWhenConcurrentTransferChangesAccountHousehold() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var destination = authTestSupport.createIdentity();
    var mover = residentOf(source.household().getId(), HouseholdRole.MEMBER);
    managerRepository.saveAndFlush(
        ProfileManager.builder()
            .accountId(destination.account().getId())
            .profileId(mover.getPersonalProfileId())
            .build());
    try {
      var deletionReached = new CountDownLatch(1);
      var releaseDeletion = new CountDownLatch(1);
      var lifecycleSpy =
          AopTestUtils.<DeviceRegistrationLifecycle>getUltimateTargetObject(registrationLifecycle);
      var lifecycleAnswer =
          mockingDetails(lifecycleSpy).getMockCreationSettings().getDefaultAnswer();
      doAnswer(
              invocation -> {
                deletionReached.countDown();
                if (!releaseDeletion.await(10, TimeUnit.SECONDS)) {
                  throw new AssertionError("Timed out releasing Account deletion");
                }

                return lifecycleAnswer.answer(invocation);
              })
          .when(lifecycleSpy)
          .revokeAllByAccount(eq(mover.getId()), any(String.class), any(Instant.class));

      Object deletion;
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var pendingDeletion =
            executor.submit(
                () ->
                    outcomeOf(
                        () ->
                            accountLifecycleService.deleteAccount(
                                authenticated(actor),
                                DeleteAccountCommand.builder()
                                    .accountId(mover.getId())
                                    .profileDisposition(ProfileDisposition.KEEP)
                                    .replacementManagerAccountId(source.account().getId())
                                    .reason("departed")
                                    .build())));
        if (!deletionReached.await(10, TimeUnit.SECONDS)) {
          throw new AssertionError(
              "Account deletion returned before the mutation gate: "
                  + pendingDeletion.get(1, TimeUnit.SECONDS));
        }

        var transfer =
            accountLifecycleService.transferAccount(
                authenticated(actor),
                TransferAccountCommand.builder()
                    .accountId(mover.getId())
                    .destinationHouseholdId(destination.household().getId())
                    .sourceAccess(SourceAccess.END)
                    .reason("relocated")
                    .build());
        assertThat(transfer).isInstanceOf(Outcome.Accepted.class);

        releaseDeletion.countDown();
        deletion = pendingDeletion.get(20, TimeUnit.SECONDS);
      }

      assertThat(deletion).isInstanceOf(Outcome.Rejected.class);
      assertThat(((Outcome.Rejected<?, ?>) deletion).rejections())
          .singleElement()
          .isInstanceOf(TransferRejections.AccountNotFound.class);
    } finally {
      authTestSupport.deleteIdentity(destination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should allow exactly one concurrent transfer of an unlinked Profile")
  void shouldAllowExactlyOneConcurrentTransferOfUnlinkedProfile() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var firstDestination = authTestSupport.createIdentity();
    var secondDestination = authTestSupport.createIdentity();
    var orphan = orphanOf(source);
    try {
      var firstGrantReached = new CountDownLatch(1);
      var releaseFirstGrant = new CountDownLatch(1);
      var firstGrant = new AtomicBoolean(true);
      var repositorySpy =
          AopTestUtils.<ProfileManagerRepository>getUltimateTargetObject(managerRepository);
      var repositoryAnswer =
          mockingDetails(repositorySpy).getMockCreationSettings().getDefaultAnswer();
      doAnswer(
              invocation -> {
                if (firstGrant.compareAndSet(true, false)) {
                  firstGrantReached.countDown();
                  if (!releaseFirstGrant.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out releasing Profile transfer");
                  }
                }

                return repositoryAnswer.answer(invocation);
          })
          .when(repositorySpy)
          .tryGrantDirectManagement(any(UUID.class), eq(orphan.getId()));

      Object firstAttempt;
      Object secondAttempt;
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var pendingFirst =
            executor.submit(
                () ->
                    outcomeOf(
                        () ->
                            profileLifecycleService.transferProfile(
                                authenticated(actor),
                                transferProfileBuilder(orphan)
                                    .destinationHouseholdId(firstDestination.household().getId())
                                    .localManagerAccountId(firstDestination.account().getId())
                                    .build())));
        assertThat(firstGrantReached.await(10, TimeUnit.SECONDS)).isTrue();
        secondAttempt =
            outcomeOf(
                () ->
                    profileLifecycleService.transferProfile(
                        authenticated(actor),
                        transferProfileBuilder(orphan)
                            .destinationHouseholdId(secondDestination.household().getId())
                            .localManagerAccountId(secondDestination.account().getId())
                            .build()));
        releaseFirstGrant.countDown();
        firstAttempt = pendingFirst.get(20, TimeUnit.SECONDS);
      }

      var attempts = List.of(firstAttempt, secondAttempt);
      assertThat(attempts)
          .as("both contenders must return protocol-independent outcomes")
          .allMatch(result -> result instanceof Outcome<?, ?>);
      assertThat(attempts)
          .filteredOn(result -> result instanceof Outcome.Accepted<?, ?>)
          .as("the home transition must have a single winner")
          .hasSize(1);
      var rejected =
          (Outcome.Rejected<?, ?>)
              attempts.stream()
                  .filter(result -> result instanceof Outcome.Rejected<?, ?>)
                  .findFirst()
                  .orElseThrow();
      assertThat(rejected.rejections())
          .singleElement()
          .isInstanceOf(TransferRejections.ProfileNotFound.class);
      var accepted =
          (Outcome.Accepted<?, ?>)
              attempts.stream()
                  .filter(result -> result instanceof Outcome.Accepted<?, ?>)
                  .findFirst()
                  .orElseThrow();
      var returnedProfile = (Profile) accepted.result();
      var storedProfile = profileRepository.findById(orphan.getId()).orElseThrow();
      assertThat(returnedProfile.getHouseholdId()).isEqualTo(storedProfile.getHouseholdId());
      var winner =
          storedProfile.getHouseholdId().equals(firstDestination.household().getId())
              ? firstDestination
              : secondDestination;
      var loser = winner == firstDestination ? secondDestination : firstDestination;
      assertThat(
              managerRepository.existsByAccountIdAndProfileId(
                  winner.account().getId(), orphan.getId()))
          .isTrue();
      assertThat(
              managerRepository.existsByAccountIdAndProfileId(
                  loser.account().getId(), orphan.getId()))
          .isFalse();
      var activeShares =
          shareRepository.findByProfileIdAndStatus(orphan.getId(), ProfileShareStatus.ACTIVE);
      assertThat(activeShares).as("only the winning home share may remain active").hasSize(1);
      assertThat(activeShares.getFirst().getHouseholdId())
          .isEqualTo(storedProfile.getHouseholdId());
      assertThat(shareRepository.findByProfileIdAndStatus(orphan.getId(), ProfileShareStatus.ENDED))
          .singleElement()
          .extracting(ProfileHouseholdShare::getHouseholdId)
          .isEqualTo(source.household().getId());
    } finally {
      authTestSupport.deleteIdentity(firstDestination);
      authTestSupport.deleteIdentity(secondDestination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should return ProfileLinked when CONNECT wins a force-delete race")
  void shouldReturnProfileLinkedWhenConnectWinsForceDeleteRace() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var home = authTestSupport.createIdentity();
    var orphan = orphanOf(home);
    try {
      var deletionReached = new CountDownLatch(1);
      var releaseDeletion = new CountDownLatch(1);
      var repositorySpy =
          AopTestUtils.<AccountInvitationRepository>getUltimateTargetObject(invitationRepository);
      var repositoryAnswer =
          mockingDetails(repositorySpy).getMockCreationSettings().getDefaultAnswer();
      doAnswer(
              invocation -> {
                deletionReached.countDown();
                if (!releaseDeletion.await(10, TimeUnit.SECONDS)) {
                  throw new AssertionError("Timed out releasing Profile deletion");
                }

                return repositoryAnswer.answer(invocation);
              })
          .when(repositorySpy)
          .invalidatePendingForProfile(eq(orphan.getId()), any(String.class), any(Instant.class));

      Object deletion;
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var pendingDeletion =
            executor.submit(
                () ->
                    outcomeOf(
                        () ->
                            profileLifecycleService.forceDeleteProfile(
                                authenticated(actor), orphan.getId(), "cleanup")));
        if (!deletionReached.await(10, TimeUnit.SECONDS)) {
          throw new AssertionError(
              "Profile deletion returned before the mutation gate: "
                  + pendingDeletion.get(1, TimeUnit.SECONDS));
        }

        linkProfile(orphan, home.household().getId());
        releaseDeletion.countDown();
        deletion = pendingDeletion.get(20, TimeUnit.SECONDS);
      }

      assertThat(deletion).isInstanceOf(Outcome.Rejected.class);
      assertThat(((Outcome.Rejected<?, ?>) deletion).rejections())
          .singleElement()
          .isInstanceOf(TransferRejections.ProfileLinked.class);
    } finally {
      authTestSupport.deleteIdentity(home);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should translate the concurrent final-Account loser into a typed rejection")
  void shouldTranslateConcurrentFinalAccountLoserIntoTypedRejection() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var household = authTestSupport.createIdentity();
    var second = residentOf(household.household().getId(), HouseholdRole.ADMIN);
    try {
      var bothPastPreflight = new CyclicBarrier(2);
      var lifecycleSpy =
          AopTestUtils.<DeviceRegistrationLifecycle>getUltimateTargetObject(registrationLifecycle);
      var lifecycleAnswer =
          mockingDetails(lifecycleSpy).getMockCreationSettings().getDefaultAnswer();
      doAnswer(
              invocation -> {
                bothPastPreflight.await(10, TimeUnit.SECONDS);
                return lifecycleAnswer.answer(invocation);
              })
          .when(lifecycleSpy)
          .revokeAllByAccount(any(UUID.class), any(String.class), any(Instant.class));

      Object firstAttempt;
      Object secondAttempt;
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var first =
            executor.submit(
                () ->
                    outcomeOf(
                        () ->
                            accountLifecycleService.deleteAccount(
                                authenticated(actor), erase(household.account().getId()))));
        var other =
            executor.submit(
                () ->
                    outcomeOf(
                        () ->
                            accountLifecycleService.deleteAccount(
                                authenticated(actor), erase(second.getId()))));
        firstAttempt = first.get(20, TimeUnit.SECONDS);
        secondAttempt = other.get(20, TimeUnit.SECONDS);
      }

      var attempts = List.of(firstAttempt, secondAttempt);
      assertThat(attempts)
          .as("both contenders must return protocol-independent outcomes")
          .allMatch(result -> result instanceof Outcome<?, ?>);
      assertThat(attempts)
          .filteredOn(result -> result instanceof Outcome.Accepted<?, ?>)
          .as("one winner was expected from %s", attempts)
          .hasSize(1);
      var rejected =
          (Outcome.Rejected<?, ?>)
              attempts.stream()
                  .filter(result -> result instanceof Outcome.Rejected<?, ?>)
                  .findFirst()
                  .orElseThrow();
      assertThat(rejected.rejections())
          .singleElement()
          .isInstanceOf(TransferRejections.FinalAccount.class);
    } finally {
      authTestSupport.deleteAccount(household.account().getId());
      authTestSupport.deleteAccount(second.getId());
      authTestSupport.deleteIdentity(actor);
    }
  }

  private UserAccount residentOf(UUID householdId, HouseholdRole role) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder().householdId(householdId).build());
          var account =
              accountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(householdId)
                      .householdRole(role)
                      .personalProfileId(profile.getId())
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(householdId)
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  private void gateTransferWrite(TransferGate gate) {
    var repositorySpy =
        AopTestUtils.<UserAccountRepository>getUltimateTargetObject(accountRepository);
    var repositoryAnswer =
        mockingDetails(repositorySpy).getMockCreationSettings().getDefaultAnswer();
    doAnswer(
            invocation -> {
              gate.transferReached().countDown();
              if (!gate.releaseTransfer().await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out releasing Account transfer");
              }

              return repositoryAnswer.answer(invocation);
            })
        .when(repositorySpy)
        .tryTransfer(
            eq(gate.mover().getId()),
            eq(gate.sourceHouseholdId()),
            any(UUID.class),
            any(HouseholdRole.class));
  }

  @Builder
  private record TransferGate(
      UserAccount mover,
      UUID sourceHouseholdId,
      CountDownLatch transferReached,
      CountDownLatch releaseTransfer) {}

  private static TransferAccountCommand transferTo(
      UserAccount mover, AuthTestSupport.TestIdentity destination) {
    return TransferAccountCommand.builder()
        .accountId(mover.getId())
        .destinationHouseholdId(destination.household().getId())
        .sourceAccess(SourceAccess.END)
        .reason("relocated")
        .build();
  }

  private Profile orphanOf(AuthTestSupport.TestIdentity manager) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(manager.household().getId())
                      .build());
          managerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(manager.account().getId())
                  .profileId(profile.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(manager.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  private void linkProfile(Profile profile, UUID householdId) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var homeShare =
              shareRepository
                  .findByProfileIdAndHouseholdIdAndStatus(
                      profile.getId(), householdId, ProfileShareStatus.ACTIVE)
                  .orElseThrow();
          homeShare.setStructural(true);
          shareRepository.saveAndFlush(homeShare);
          accountRepository.saveAndFlush(
              AccountFixture.defaultAccountBuilder()
                  .householdId(householdId)
                  .householdRole(HouseholdRole.MEMBER)
                  .personalProfileId(profile.getId())
                  .build());
        });
  }

  private static TransferProfileCommand.TransferProfileCommandBuilder transferProfileBuilder(
      Profile profile) {
    return TransferProfileCommand.builder().profileId(profile.getId()).reason("recovery");
  }

  private static DeleteAccountCommand erase(UUID accountId) {
    return DeleteAccountCommand.builder()
        .accountId(accountId)
        .profileDisposition(ProfileDisposition.ERASE)
        .reason("departed")
        .build();
  }

  private static AuthenticatedIdentity authenticated(AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.builder()
        .accountId(identity.account().getId())
        .authSessionId(identity.session().getId())
        .scope(TokenScope.ACCOUNT)
        .householdId(identity.household().getId())
        .householdRole(identity.account().getHouseholdRole())
        .contextHouseholdId(identity.household().getId())
        .reauthenticatedAt(Optional.of(Instant.now().minusSeconds(1)))
        .build();
  }

  private static Outcome<?, ?> outcomeOf(Supplier<? extends Outcome<?, ?>> attempt) {
    return attempt.get();
  }
}
