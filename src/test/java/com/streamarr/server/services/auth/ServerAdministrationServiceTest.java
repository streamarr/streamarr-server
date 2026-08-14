package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.domain.auth.ProfileDeletionMode;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.ProfileManagerInvariantException;
import com.streamarr.server.exceptions.ServerAdministrationDeniedException;
import com.streamarr.server.fakes.FakeProfileDeletionAuthorizationRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeProfileSelectionCleaner;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Server Administration Service Tests")
class ServerAdministrationServiceTest {

  private static final String PASSWORD = "correct horse battery staple";

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeProfileManagerRepository managerRepository = new FakeProfileManagerRepository();
  private final FakeProfileManagerInvitationRepository invitationRepository =
      new FakeProfileManagerInvitationRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileDeletionAuthorizationRepository deletionAuthorizationRepository =
      new FakeProfileDeletionAuthorizationRepository();
  private final FakeProfileSelectionCleaner selectionCleaner = new FakeProfileSelectionCleaner();
  private final FakeSecurityAuditEventRepository auditRepository =
      new FakeSecurityAuditEventRepository();
  private final PasswordEncoder passwordEncoder =
      PasswordEncoderFactories.createDelegatingPasswordEncoder();
  private final ServerAdministrationService service =
      new ServerAdministrationService(
          accountRepository,
          profileRepository,
          managerRepository,
          invitationRepository,
          shareRepository,
          deletionAuthorizationRepository,
          selectionCleaner,
          new ServerAdminAuthorizer(accountRepository, passwordEncoder),
          new KidProfileManagerPolicy(
              profileRepository, managerRepository, shareRepository, accountRepository),
          new SecurityAuditService(auditRepository));

  @Test
  @DisplayName("Should force delete a profile and every relationship after fresh reauthentication")
  void shouldForceDeleteProfileAndEveryRelationshipAfterFreshReauthentication() {
    var admin = saveAccount(AccountRole.ADMIN);
    var otherManager = saveAccount(AccountRole.USER);
    var profile = profileRepository.save(Profile.builder().name("Disputed Profile").build());
    managerRepository.save(manager(admin.getId(), profile.getId()));
    managerRepository.save(manager(otherManager.getId(), profile.getId()));
    invitationRepository.save(
        ProfileManagerInvitation.builder()
            .profileId(profile.getId())
            .invitingAccountId(admin.getId())
            .invitedAccountId(UUID.randomUUID())
            .status(ProfileManagerInvitationStatus.PENDING)
            .build());
    var activeShare = saveShare(profile.getId(), ProfileShareStatus.ACTIVE);
    var pendingShare = saveShare(profile.getId(), ProfileShareStatus.PENDING);

    service.forceDeleteProfile(
        ForceProfileDeletionCommand.builder()
            .actingAccountId(admin.getId())
            .profileId(profile.getId())
            .password(PASSWORD)
            .reason("Lost access recovery")
            .build());

    assertThat(profileRepository.existsById(profile.getId())).isFalse();
    assertThat(managerRepository.findByProfileId(profile.getId())).isEmpty();
    assertThat(invitationRepository.findAll()).isEmpty();
    assertThat(shareRepository.countByProfileId(profile.getId())).isZero();
    assertThat(selectionCleaner.clearedSelections)
        .extracting(FakeProfileSelectionCleaner.ClearedSelection::householdId)
        .containsExactlyInAnyOrder(activeShare.getHouseholdId(), pendingShare.getHouseholdId());
    assertThat(deletionAuthorizationRepository.findAll())
        .singleElement()
        .satisfies(
            authorization -> {
              assertThat(authorization.getActingAccountId()).isEqualTo(admin.getId());
              assertThat(authorization.getMode()).isEqualTo(ProfileDeletionMode.FORCE);
            });
    assertThat(auditRepository.findAll())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getOperation())
                  .isEqualTo(SecurityAuditOperation.PROFILE_FORCE_DELETED);
              assertThat(event.getReason()).isEqualTo("Lost access recovery");
            });
  }

  @Test
  @DisplayName("Should remove profile shares in global household guard order")
  void shouldRemoveProfileSharesInGlobalHouseholdGuardOrder() {
    var admin = saveAccount(AccountRole.ADMIN);
    var profile = profileRepository.save(Profile.builder().name("Ordered Deletion").build());
    managerRepository.save(manager(admin.getId(), profile.getId()));
    var firstHouseholdId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    var secondHouseholdId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(secondHouseholdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(firstHouseholdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());

    service.forceDeleteProfile(
        ForceProfileDeletionCommand.builder()
            .actingAccountId(admin.getId())
            .profileId(profile.getId())
            .password(PASSWORD)
            .reason("Lost access recovery")
            .build());

    assertThat(selectionCleaner.clearedSelections)
        .extracting(FakeProfileSelectionCleaner.ClearedSelection::householdId)
        .containsExactly(firstHouseholdId, secondHouseholdId);
  }

  @Test
  @DisplayName("Should force unshare only the targeted profile share")
  void shouldForceUnshareOnlyTargetedProfileShare() {
    var admin = saveAccount(AccountRole.ADMIN);
    var profile = profileRepository.save(Profile.builder().name("Portable Profile").build());
    var removedShare = saveShare(profile.getId(), ProfileShareStatus.ACTIVE);
    var retainedShare = saveShare(profile.getId(), ProfileShareStatus.ACTIVE);

    service.forceUnshareProfile(
        ForceProfileUnshareCommand.builder()
            .actingAccountId(admin.getId())
            .shareId(removedShare.getId())
            .password(PASSWORD)
            .reason("Household recovery")
            .build());

    assertThat(shareRepository.existsById(removedShare.getId())).isFalse();
    assertThat(shareRepository.existsById(retainedShare.getId())).isTrue();
    assertThat(selectionCleaner.clearedSelections)
        .containsExactly(
            new FakeProfileSelectionCleaner.ClearedSelection(
                profile.getId(), removedShare.getHouseholdId()));
    assertThat(auditRepository.findAll())
        .singleElement()
        .extracting(event -> event.getOperation())
        .isEqualTo(SecurityAuditOperation.PROFILE_FORCE_UNSHARED);
  }

  @Test
  @DisplayName("Should override a disputed profile manager relationship")
  void shouldOverrideDisputedProfileManagerRelationship() {
    var admin = saveAccount(AccountRole.ADMIN);
    var manager = saveAccount(AccountRole.USER);
    var profile = profileRepository.save(Profile.builder().name("Managed Profile").build());

    service.overrideProfileManager(
        ProfileManagerOverrideCommand.builder()
            .actingAccountId(admin.getId())
            .targetAccountId(manager.getId())
            .profileId(profile.getId())
            .action(ProfileManagerOverrideAction.GRANT)
            .password(PASSWORD)
            .reason("Restore named manager")
            .build());

    assertThat(managerRepository.existsByAccountIdAndProfileId(manager.getId(), profile.getId()))
        .isTrue();
    assertThat(auditRepository.findAll())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getTargetAccountId()).isEqualTo(manager.getId());
              assertThat(event.getOperation())
                  .isEqualTo(SecurityAuditOperation.PROFILE_MANAGER_OVERRIDDEN);
            });
  }

  @Test
  @DisplayName("Should leave existing manager relationship unchanged when grant repeated")
  void shouldLeaveExistingManagerRelationshipUnchangedWhenGrantRepeated() {
    var admin = saveAccount(AccountRole.ADMIN);
    var existingManager = saveAccount(AccountRole.USER);
    var profile = profileRepository.save(Profile.builder().name("Managed Profile").build());
    managerRepository.save(manager(existingManager.getId(), profile.getId()));

    service.overrideProfileManager(
        ProfileManagerOverrideCommand.builder()
            .actingAccountId(admin.getId())
            .targetAccountId(existingManager.getId())
            .profileId(profile.getId())
            .action(ProfileManagerOverrideAction.GRANT)
            .password(PASSWORD)
            .reason("Confirm named manager")
            .build());

    assertThat(managerRepository.findByProfileId(profile.getId())).hasSize(1);
  }

  @Test
  @DisplayName("Should make concurrent manager grants idempotent")
  void shouldMakeConcurrentManagerGrantsIdempotent() throws Exception {
    var admin = saveAccount(AccountRole.ADMIN);
    var target = saveAccount(AccountRole.USER);
    var profile = profileRepository.save(Profile.builder().name("Concurrent Grant").build());
    var racingManagerRepository =
        new RacingProfileManagerRepository(target.getId(), profile.getId());
    var racingService =
        new ServerAdministrationService(
            accountRepository,
            profileRepository,
            racingManagerRepository,
            invitationRepository,
            shareRepository,
            deletionAuthorizationRepository,
            selectionCleaner,
            new ServerAdminAuthorizer(accountRepository, passwordEncoder),
            new KidProfileManagerPolicy(
                profileRepository, racingManagerRepository, shareRepository, accountRepository),
            new SecurityAuditService(auditRepository));
    var command =
        ProfileManagerOverrideCommand.builder()
            .actingAccountId(admin.getId())
            .targetAccountId(target.getId())
            .profileId(profile.getId())
            .action(ProfileManagerOverrideAction.GRANT)
            .password(PASSWORD)
            .reason("Concurrent recovery")
            .build();

    List<Throwable> failures;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(() -> catchFailure(() -> racingService.overrideProfileManager(command)));
      var second =
          executor.submit(() -> catchFailure(() -> racingService.overrideProfileManager(command)));
      failures = Arrays.asList(first.get(), second.get());
    }

    assertThat(failures).containsOnlyNulls();
    assertThat(racingManagerRepository.findByProfileId(profile.getId())).hasSize(1);
  }

  @Test
  @DisplayName("Should remove one of multiple adult profile managers")
  void shouldRemoveOneOfMultipleAdultProfileManagers() {
    var admin = saveAccount(AccountRole.ADMIN);
    var removedManager = saveAccount(AccountRole.USER);
    var retainedManager = saveAccount(AccountRole.USER);
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Managed Adult")
                .classification(ProfileClassification.ADULT)
                .build());
    managerRepository.save(manager(removedManager.getId(), profile.getId()));
    managerRepository.save(manager(retainedManager.getId(), profile.getId()));

    service.overrideProfileManager(
        ProfileManagerOverrideCommand.builder()
            .actingAccountId(admin.getId())
            .targetAccountId(removedManager.getId())
            .profileId(profile.getId())
            .action(ProfileManagerOverrideAction.REMOVE)
            .password(PASSWORD)
            .reason("Remove disputed manager")
            .build());

    assertThat(
            managerRepository.existsByAccountIdAndProfileId(
                removedManager.getId(), profile.getId()))
        .isFalse();
    assertThat(
            managerRepository.existsByAccountIdAndProfileId(
                retainedManager.getId(), profile.getId()))
        .isTrue();
  }

  @Test
  @DisplayName("Should reject removing absent or sole profile manager")
  void shouldRejectRemovingAbsentOrSoleProfileManager() {
    var admin = saveAccount(AccountRole.ADMIN);
    var target = saveAccount(AccountRole.USER);
    var profile = profileRepository.save(Profile.builder().name("Managed Profile").build());
    var absentRemoval =
        ProfileManagerOverrideCommand.builder()
            .actingAccountId(admin.getId())
            .targetAccountId(target.getId())
            .profileId(profile.getId())
            .action(ProfileManagerOverrideAction.REMOVE)
            .password(PASSWORD)
            .reason("Remove absent manager")
            .build();

    assertThatThrownBy(() -> service.overrideProfileManager(absentRemoval))
        .isInstanceOf(ProfileAccessDeniedException.class);

    managerRepository.save(manager(target.getId(), profile.getId()));
    var soleRemoval =
        ProfileManagerOverrideCommand.builder()
            .actingAccountId(admin.getId())
            .targetAccountId(target.getId())
            .profileId(profile.getId())
            .action(ProfileManagerOverrideAction.REMOVE)
            .password(PASSWORD)
            .reason("Remove sole manager")
            .build();

    assertThatThrownBy(() -> service.overrideProfileManager(soleRemoval))
        .isInstanceOf(ProfileManagerInvariantException.class);
  }

  @Test
  @DisplayName("Should require nonblank reason for destructive administration")
  void shouldRequireNonblankReasonForDestructiveAdministration() {
    var actorId = UUID.randomUUID();
    var missingReason =
        ForceProfileDeletionCommand.builder()
            .actingAccountId(actorId)
            .profileId(UUID.randomUUID())
            .password(PASSWORD)
            .reason("")
            .build();
    var blankReason =
        ForceProfileUnshareCommand.builder()
            .actingAccountId(actorId)
            .shareId(UUID.randomUUID())
            .password(PASSWORD)
            .reason("  ")
            .build();

    assertThatThrownBy(() -> service.forceDeleteProfile(missingReason))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.forceUnshareProfile(blankReason))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject global administration from a non administrator")
  void shouldRejectGlobalAdministrationFromNonAdministrator() {
    var account = saveAccount(AccountRole.USER);
    var profile = profileRepository.save(Profile.builder().name("Protected Profile").build());
    var command =
        ForceProfileDeletionCommand.builder()
            .actingAccountId(account.getId())
            .profileId(profile.getId())
            .password(PASSWORD)
            .reason("Unauthorized")
            .build();

    assertThatThrownBy(() -> service.forceDeleteProfile(command))
        .isInstanceOf(ServerAdministrationDeniedException.class);

    assertThat(profileRepository.existsById(profile.getId())).isTrue();
    assertThat(auditRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should reject destructive override when password reauthentication fails")
  void shouldRejectDestructiveOverrideWhenPasswordReauthenticationFails() {
    var admin = saveAccount(AccountRole.ADMIN);
    var profile = profileRepository.save(Profile.builder().name("Protected Profile").build());
    var command =
        ForceProfileDeletionCommand.builder()
            .actingAccountId(admin.getId())
            .profileId(profile.getId())
            .password("wrong password")
            .reason("Recovery")
            .build();

    assertThatThrownBy(() -> service.forceDeleteProfile(command))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(profileRepository.existsById(profile.getId())).isTrue();
    assertThat(auditRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should reject manager override that removes the last local kid parent")
  void shouldRejectManagerOverrideThatRemovesLastLocalKidParent() {
    var localHouseholdId = UUID.randomUUID();
    var admin = saveAccount(AccountRole.ADMIN);
    var localParent = saveAccount(AccountRole.USER);
    localParent.setHomeHouseholdId(localHouseholdId);
    localParent.setHouseholdRole(HouseholdRole.PARENT);
    accountRepository.save(localParent);
    var remoteParent = saveAccount(AccountRole.USER);
    var kid =
        profileRepository.save(
            Profile.builder()
                .name("Portable Kid")
                .classification(ProfileClassification.KID)
                .maximumAllowedRatingAge(7)
                .build());
    managerRepository.save(manager(localParent.getId(), kid.getId()));
    managerRepository.save(manager(remoteParent.getId(), kid.getId()));
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(kid.getId())
            .householdId(localHouseholdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var command =
        ProfileManagerOverrideCommand.builder()
            .actingAccountId(admin.getId())
            .targetAccountId(localParent.getId())
            .profileId(kid.getId())
            .action(ProfileManagerOverrideAction.REMOVE)
            .password(PASSWORD)
            .reason("Unsafe override")
            .build();

    assertThatThrownBy(() -> service.overrideProfileManager(command))
        .isInstanceOf(KidProfileManagerRequiredException.class);

    assertThat(managerRepository.existsByAccountIdAndProfileId(localParent.getId(), kid.getId()))
        .isTrue();
  }

  private UserAccount saveAccount(AccountRole role) {
    return accountRepository.save(
        UserAccount.builder()
            .email("account-" + UUID.randomUUID() + "@example.com")
            .displayName("Account")
            .passwordHash(passwordEncoder.encode(PASSWORD))
            .accountRole(role)
            .homeHouseholdId(UUID.randomUUID())
            .householdRole(HouseholdRole.OWNER)
            .build());
  }

  private ProfileManager manager(UUID accountId, UUID profileId) {
    return ProfileManager.builder().accountId(accountId).profileId(profileId).build();
  }

  private ProfileHouseholdShare saveShare(UUID profileId, ProfileShareStatus status) {
    return shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(UUID.randomUUID())
            .status(status)
            .build());
  }

  private Throwable catchFailure(Runnable operation) {
    try {
      operation.run();
      return null;
    } catch (RuntimeException exception) {
      return exception;
    }
  }

  private static final class RacingProfileManagerRepository extends FakeProfileManagerRepository {

    private final UUID targetAccountId;
    private final UUID targetProfileId;
    private final CyclicBarrier barrier = new CyclicBarrier(2);

    private RacingProfileManagerRepository(UUID targetAccountId, UUID targetProfileId) {
      this.targetAccountId = targetAccountId;
      this.targetProfileId = targetProfileId;
    }

    @Override
    public boolean insertIfAbsent(UUID accountId, UUID profileId) {
      var exists = existsByAccountIdAndProfileId(accountId, profileId);
      if (!targetAccountId.equals(accountId) || !targetProfileId.equals(profileId)) {
        return super.insertIfAbsent(accountId, profileId);
      }

      try {
        barrier.await();
      } catch (Exception exception) {
        throw new IllegalStateException("Manager grant race barrier failed", exception);
      }
      if (exists) {
        return false;
      }
      return super.insertIfAbsent(accountId, profileId);
    }
  }
}
