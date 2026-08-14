package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.domain.auth.ProfileDeletionAuthorization;
import com.streamarr.server.domain.auth.ProfileDeletionMode;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileDeletionAuthorizationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Portable Identity Setup Integration Tests")
class PortableIdentitySetupIT extends AbstractIntegrationTest {

  @Autowired private SetupService setupService;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private ProfileManagerRepository profileManagerRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private ProfileHouseholdShareRepository profileShareRepository;

  @Autowired private ProfileRepository profileRepository;

  @Autowired private ProfileDeletionAuthorizationRepository deletionAuthorizationRepository;

  @Autowired private ProfileManagerInvitationRepository invitationRepository;

  @Autowired private SecurityAuditEventRepository auditRepository;

  @Autowired private ServerAdministrationService serverAdministrationService;

  @Autowired private HouseholdAdministrationService householdAdministrationService;

  @Autowired private AuthSessionRepository authSessionRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  @Transactional
  @DisplayName("Should persist owned home and portable profile when setup completes")
  void shouldPersistOwnedHomeAndPortableProfileWhenSetupCompletes() {
    var suffix = UUID.randomUUID();

    var result =
        setupService.setup(
            SetupCommand.builder()
                .email("portable-" + suffix + "@example.com")
                .displayName("Portable Admin")
                .password("correct horse battery staple")
                .householdName("Portable Home")
                .profileName("Portable Profile")
                .build());

    var account = userAccountRepository.findById(result.admin().getId()).orElseThrow();
    assertThat(account.getHomeHouseholdId()).isEqualTo(result.household().getId());
    assertThat(account.getHouseholdRole()).isEqualTo(HouseholdRole.OWNER);

    assertThat(profileManagerRepository.findAll())
        .anySatisfy(
            manager -> {
              assertThat(manager.getAccountId()).isEqualTo(account.getId());
              assertThat(manager.getProfileId()).isEqualTo(result.profile().getId());
            });
    assertThat(profileShareRepository.findAll())
        .anySatisfy(
            share -> {
              assertThat(share.getProfileId()).isEqualTo(result.profile().getId());
              assertThat(share.getHouseholdId()).isEqualTo(result.household().getId());
              assertThat(share.getStatus()).isEqualTo(ProfileShareStatus.ACTIVE);
            });
    assertThat(result.profile().getClassification()).isEqualTo(ProfileClassification.ADULT);
    assertThat(result.profile().getMaximumAllowedRatingAge()).isNull();
    assertThat(result.profile().getPinHash()).isNull();
    assertThat(result.profile().getManagementVersion()).isZero();
    assertThat(result.household().getSafetyVersion()).isZero();
  }

  @Test
  @Transactional
  @DisplayName("Should reject second owner when household already has owner")
  void shouldRejectSecondOwnerWhenHouseholdAlreadyHasOwner() {
    var suffix = UUID.randomUUID();
    var setup =
        setupService.setup(
            SetupCommand.builder()
                .email("owner-" + suffix + "@example.com")
                .displayName("First Owner")
                .password("correct horse battery staple")
                .householdName("Owned Home")
                .profileName("Owner Profile")
                .build());
    var secondOwner =
        UserAccount.builder()
            .email("second-owner-" + suffix + "@example.com")
            .displayName("Second Owner")
            .passwordHash("{noop}not-a-real-hash")
            .accountRole(AccountRole.USER)
            .homeHouseholdId(setup.household().getId())
            .householdRole(HouseholdRole.OWNER)
            .build();

    assertThatThrownBy(() -> userAccountRepository.saveAndFlush(secondOwner))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_user_account_household_owner");
  }

  @Test
  @DisplayName("Should reject demoting the sole household owner")
  void shouldRejectDemotingTheSoleHouseholdOwner() {
    var setup = createPortableIdentity("Sole Owner");
    var ownerAccountId = setup.accountId();

    assertThatThrownBy(() -> demoteSoleOwner(ownerAccountId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("must have exactly one owner");
  }

  @Test
  @DisplayName("Should reject committing a profile without a manager")
  void shouldRejectCommittingProfileWithoutManager() {
    assertThatThrownBy(this::commitManagerlessProfile)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("must have at least one manager");
  }

  @Test
  @DisplayName("Should guard profile when pending manager invitation is created")
  void shouldGuardProfileWhenPendingManagerInvitationIsCreated() {
    var managedIdentity = createPortableIdentity("Managed Invitation");
    var invitedIdentity = createPortableIdentity("Invited Manager");
    var versionBeforeInvitation =
        profileRepository
            .findById(managedIdentity.profileId())
            .orElseThrow()
            .getManagementVersion();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ ->
                invitationRepository.save(
                    ProfileManagerInvitation.builder()
                        .profileId(managedIdentity.profileId())
                        .invitingAccountId(managedIdentity.accountId())
                        .invitedAccountId(invitedIdentity.accountId())
                        .status(ProfileManagerInvitationStatus.PENDING)
                        .build()));

    assertThat(
            profileRepository
                .findById(managedIdentity.profileId())
                .orElseThrow()
                .getManagementVersion())
        .isGreaterThan(versionBeforeInvitation);
  }

  @Test
  @DisplayName("Should guard profile before ordinary deletion is authorized")
  void shouldGuardProfileBeforeOrdinaryDeletionIsAuthorized() {
    var identity = createPortableIdentity("Guarded Deletion");
    var versionBeforeAuthorization =
        profileRepository.findById(identity.profileId()).orElseThrow().getManagementVersion();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ ->
                deletionAuthorizationRepository.saveAndFlush(
                    ProfileDeletionAuthorization.builder()
                        .profileId(identity.profileId())
                        .actingAccountId(identity.accountId())
                        .mode(ProfileDeletionMode.ORDINARY)
                        .build()));

    assertThat(
            profileRepository.findById(identity.profileId()).orElseThrow().getManagementVersion())
        .isGreaterThan(versionBeforeAuthorization);
  }

  @Test
  @DisplayName("Should reject active kid share when household adult profile has no PIN")
  void shouldRejectActiveKidShareWhenHouseholdAdultProfileHasNoPin() {
    var setup = createPortableIdentity("Safety Owner");
    var accountId = setup.accountId();
    var householdId = setup.householdId();

    assertThatThrownBy(() -> activateKidShareWithoutAdultPin(accountId, householdId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("requires an effective PIN");
  }

  @Test
  @DisplayName("Should reject removing the last local parent manager from an active kid share")
  void shouldRejectRemovingLastLocalParentManagerFromActiveKidShare() {
    var local = createPortableIdentity("Local Parent");
    var remote = createPortableIdentity("Remote Parent");
    var kidId =
        new TransactionTemplate(transactionManager)
            .execute(
                _ -> {
                  var localAdult = profileRepository.findById(local.profileId()).orElseThrow();
                  localAdult.setPinHash("encoded-pin");
                  profileRepository.save(localAdult);
                  var kid =
                      profileRepository.save(
                          Profile.builder()
                              .name("Shared Kid")
                              .classification(ProfileClassification.KID)
                              .maximumAllowedRatingAge(7)
                              .build());
                  profileManagerRepository.save(
                      ProfileManager.builder()
                          .accountId(local.accountId())
                          .profileId(kid.getId())
                          .build());
                  profileManagerRepository.save(
                      ProfileManager.builder()
                          .accountId(remote.accountId())
                          .profileId(kid.getId())
                          .build());
                  profileShareRepository.save(
                      ProfileHouseholdShare.builder()
                          .profileId(kid.getId())
                          .householdId(local.householdId())
                          .status(ProfileShareStatus.ACTIVE)
                          .build());
                  return kid.getId();
                });
    var localAccountId = local.accountId();

    assertThatThrownBy(() -> removeLocalManager(kidId, localAccountId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("requires a local owner or parent manager");
  }

  @Test
  @DisplayName("Should reject moving the last local parent manager away from an active kid share")
  void shouldRejectMovingLastLocalParentManagerAwayFromActiveKidShare() {
    var local = createPortableIdentity("Account Move Local");
    var remote = createPortableIdentity("Account Move Remote");
    var localParentId =
        new TransactionTemplate(transactionManager)
            .execute(
                _ -> {
                  var localAdult = profileRepository.findById(local.profileId()).orElseThrow();
                  localAdult.setPinHash("encoded-pin");
                  profileRepository.save(localAdult);
                  var localParent =
                      userAccountRepository.save(
                          UserAccount.builder()
                              .email("local-parent-" + UUID.randomUUID() + "@example.com")
                              .displayName("Local Parent")
                              .passwordHash("encoded")
                              .accountRole(AccountRole.USER)
                              .homeHouseholdId(local.householdId())
                              .householdRole(HouseholdRole.PARENT)
                              .build());
                  var kid =
                      profileRepository.save(
                          Profile.builder()
                              .name("Account Move Kid")
                              .classification(ProfileClassification.KID)
                              .maximumAllowedRatingAge(7)
                              .build());
                  profileManagerRepository.save(
                      ProfileManager.builder()
                          .accountId(localParent.getId())
                          .profileId(kid.getId())
                          .build());
                  profileManagerRepository.save(
                      ProfileManager.builder()
                          .accountId(remote.accountId())
                          .profileId(kid.getId())
                          .build());
                  profileShareRepository.save(
                      ProfileHouseholdShare.builder()
                          .profileId(kid.getId())
                          .householdId(local.householdId())
                          .status(ProfileShareStatus.ACTIVE)
                          .build());
                  return localParent.getId();
                });
    var remoteHouseholdId = remote.householdId();

    assertThatThrownBy(() -> moveLocalParent(localParentId, remoteHouseholdId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("requires a local owner or parent manager");
  }

  @Test
  @DisplayName("Should reject deleting a profile without explicit deletion authorization")
  void shouldRejectDeletingProfileWithoutExplicitDeletionAuthorization() {
    var setup = createPortableIdentity("Unauthorized Delete");
    var profileId = setup.profileId();

    assertThatThrownBy(() -> deleteProfileWithoutAuthorization(profileId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("requires explicit authorization");
  }

  @Test
  @DisplayName("Should reject ordinary authorized deletion while profile remains shared")
  void shouldRejectOrdinaryAuthorizedDeletionWhileProfileRemainsShared() {
    var setup = createPortableIdentity("Shared Delete");
    var accountId = setup.accountId();
    var profileId = setup.profileId();

    assertThatThrownBy(() -> deleteSharedProfile(accountId, profileId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("requires no household shares");
  }

  @Test
  @DisplayName("Should atomically force delete a shared co-managed profile as ServerAdmin")
  void shouldAtomicallyForceDeleteSharedCoManagedProfileAsServerAdmin() {
    var primary = createPortableIdentity("Force Delete Admin");
    var secondary = createPortableIdentity("Force Delete Manager");
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              var admin = userAccountRepository.findById(primary.accountId()).orElseThrow();
              admin.setAccountRole(AccountRole.ADMIN);
              admin.setPasswordHash(passwordEncoder.encode("server-admin-password"));
              userAccountRepository.save(admin);
              profileManagerRepository.save(
                  ProfileManager.builder()
                      .accountId(secondary.accountId())
                      .profileId(primary.profileId())
                      .build());
              invitationRepository.save(
                  ProfileManagerInvitation.builder()
                      .profileId(primary.profileId())
                      .invitingAccountId(primary.accountId())
                      .invitedAccountId(secondary.accountId())
                      .status(ProfileManagerInvitationStatus.PENDING)
                      .build());
            });

    serverAdministrationService.forceDeleteProfile(
        ForceProfileDeletionCommand.builder()
            .actingAccountId(primary.accountId())
            .profileId(primary.profileId())
            .password("server-admin-password")
            .reason("Recover disputed profile")
            .build());

    assertThat(profileRepository.existsById(primary.profileId())).isFalse();
    assertThat(profileShareRepository.findByProfileId(primary.profileId())).isEmpty();
    assertThat(profileManagerRepository.findByProfileId(primary.profileId())).isEmpty();
    assertThat(invitationRepository.findByProfileId(primary.profileId())).isEmpty();
    assertThat(auditRepository.findAll())
        .anySatisfy(
            event -> {
              assertThat(event.getTargetProfileId()).isEqualTo(primary.profileId());
              assertThat(event.getReason()).isEqualTo("Recover disputed profile");
            });
  }

  @Test
  @DisplayName("Should transfer account and clear profile selection in the same transaction")
  void shouldTransferAccountAndClearProfileSelectionInSameTransaction() {
    var source = createPortableIdentity("Transfer Source");
    var target = createPortableIdentity("Transfer Target");
    var transferredAccountId =
        new TransactionTemplate(transactionManager)
            .execute(
                _ -> {
                  var admin = userAccountRepository.findById(source.accountId()).orElseThrow();
                  admin.setAccountRole(AccountRole.ADMIN);
                  admin.setPasswordHash(passwordEncoder.encode("transfer-admin-password"));
                  userAccountRepository.save(admin);
                  var transferred =
                      userAccountRepository.save(
                          UserAccount.builder()
                              .email("transferred-" + UUID.randomUUID() + "@example.com")
                              .displayName("Transferred Account")
                              .passwordHash("encoded")
                              .accountRole(AccountRole.USER)
                              .homeHouseholdId(source.householdId())
                              .householdRole(HouseholdRole.MEMBER)
                              .build());
                  authSessionRepository.save(
                      AuthSession.builder()
                          .accountId(transferred.getId())
                          .deviceName("Transfer Device")
                          .activeProfileId(source.profileId())
                          .build());
                  return transferred.getId();
                });

    householdAdministrationService.transferAccount(
        AccountHouseholdTransferCommand.builder()
            .actingAccountId(source.accountId())
            .targetAccountId(transferredAccountId)
            .targetHouseholdId(target.householdId())
            .targetRole(HouseholdRole.PARENT)
            .password("transfer-admin-password")
            .reason("Move account home")
            .build());

    var transferred = userAccountRepository.findById(transferredAccountId).orElseThrow();
    assertThat(transferred.getHomeHouseholdId()).isEqualTo(target.householdId());
    assertThat(transferred.getHouseholdRole()).isEqualTo(HouseholdRole.PARENT);
    assertThat(authSessionRepository.findByAccountId(transferredAccountId))
        .singleElement()
        .extracting(AuthSession::getActiveProfileId)
        .isNull();
  }

  @Test
  @DisplayName("Should transfer exact household ownership without an intermediate committed gap")
  void shouldTransferExactHouseholdOwnershipWithoutIntermediateCommittedGap() {
    var setup = createPortableIdentity("Ownership Transfer");
    var nextOwnerId =
        new TransactionTemplate(transactionManager)
            .execute(
                _ -> {
                  var owner = userAccountRepository.findById(setup.accountId()).orElseThrow();
                  owner.setPasswordHash(passwordEncoder.encode("owner-password"));
                  userAccountRepository.save(owner);
                  return userAccountRepository
                      .save(
                          UserAccount.builder()
                              .email("next-owner-" + UUID.randomUUID() + "@example.com")
                              .displayName("Next Owner")
                              .passwordHash("encoded")
                              .accountRole(AccountRole.USER)
                              .homeHouseholdId(setup.householdId())
                              .householdRole(HouseholdRole.PARENT)
                              .build())
                      .getId();
                });

    householdAdministrationService.transferOwnership(
        HouseholdOwnershipTransferCommand.builder()
            .actingAccountId(setup.accountId())
            .householdId(setup.householdId())
            .targetAccountId(nextOwnerId)
            .password("owner-password")
            .reason("Planned handoff")
            .build());

    assertThat(userAccountRepository.findById(setup.accountId()).orElseThrow().getHouseholdRole())
        .isEqualTo(HouseholdRole.PARENT);
    assertThat(userAccountRepository.findById(nextOwnerId).orElseThrow().getHouseholdRole())
        .isEqualTo(HouseholdRole.OWNER);
  }

  @Test
  @DisplayName("Should retain one global profile policy when shared into two households")
  void shouldRetainOneGlobalProfilePolicyWhenSharedIntoTwoHouseholds() {
    var first = createPortableIdentity("First Shared Home");
    var second = createPortableIdentity("Second Shared Home");

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              var profile = profileRepository.findById(first.profileId()).orElseThrow();
              profile.setPinHash("one-global-pin");
              profile.setMaximumAllowedRatingAge(16);
              profileRepository.save(profile);
              profileShareRepository.save(
                  ProfileHouseholdShare.builder()
                      .profileId(first.profileId())
                      .householdId(second.householdId())
                      .status(ProfileShareStatus.ACTIVE)
                      .build());
            });

    assertThat(
            profileShareRepository.findByProfileIdAndStatus(
                first.profileId(), ProfileShareStatus.ACTIVE))
        .extracting(ProfileHouseholdShare::getHouseholdId)
        .containsExactlyInAnyOrder(first.householdId(), second.householdId());
    assertThat(profileRepository.findAll())
        .filteredOn(profile -> profile.getId().equals(first.profileId()))
        .singleElement()
        .satisfies(
            profile -> {
              assertThat(profile.getPinHash()).isEqualTo("one-global-pin");
              assertThat(profile.getMaximumAllowedRatingAge()).isEqualTo(16);
            });
  }

  private void demoteSoleOwner(UUID ownerAccountId) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              var owner = userAccountRepository.findById(ownerAccountId).orElseThrow();
              owner.setHouseholdRole(HouseholdRole.PARENT);
              userAccountRepository.saveAndFlush(owner);
            });
  }

  private void commitManagerlessProfile() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ ->
                profileRepository.saveAndFlush(
                    Profile.builder().name("Managerless Profile").build()));
  }

  private void activateKidShareWithoutAdultPin(UUID accountId, UUID householdId) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              var kid =
                  profileRepository.save(
                      Profile.builder()
                          .name("Restricted Kid")
                          .classification(ProfileClassification.KID)
                          .maximumAllowedRatingAge(7)
                          .build());
              profileManagerRepository.save(
                  ProfileManager.builder().accountId(accountId).profileId(kid.getId()).build());
              profileShareRepository.save(
                  ProfileHouseholdShare.builder()
                      .profileId(kid.getId())
                      .householdId(householdId)
                      .status(ProfileShareStatus.ACTIVE)
                      .build());
            });
  }

  private void removeLocalManager(UUID profileId, UUID localAccountId) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              var localManager =
                  profileManagerRepository.findByProfileId(profileId).stream()
                      .filter(manager -> manager.getAccountId().equals(localAccountId))
                      .findFirst()
                      .orElseThrow();
              profileManagerRepository.delete(localManager);
            });
  }

  private void moveLocalParent(UUID localParentId, UUID remoteHouseholdId) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              var localParent = userAccountRepository.findById(localParentId).orElseThrow();
              localParent.setHomeHouseholdId(remoteHouseholdId);
              userAccountRepository.save(localParent);
            });
  }

  private void deleteProfileWithoutAuthorization(UUID profileId) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(_ -> profileRepository.deleteById(profileId));
  }

  private void deleteSharedProfile(UUID accountId, UUID profileId) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              deletionAuthorizationRepository.saveAndFlush(
                  ProfileDeletionAuthorization.builder()
                      .profileId(profileId)
                      .actingAccountId(accountId)
                      .mode(ProfileDeletionMode.ORDINARY)
                      .build());
              profileRepository.deleteById(profileId);
            });
  }

  private PortableIdentity createPortableIdentity(String displayName) {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var household =
                  householdRepository.save(Household.builder().name(displayName + " Home").build());
              var account =
                  userAccountRepository.save(
                      UserAccount.builder()
                          .email("identity-" + UUID.randomUUID() + "@example.com")
                          .displayName(displayName)
                          .passwordHash("{noop}not-a-real-hash")
                          .accountRole(AccountRole.USER)
                          .homeHouseholdId(household.getId())
                          .householdRole(HouseholdRole.OWNER)
                          .build());
              var profile =
                  profileRepository.save(Profile.builder().name("Unprotected Adult").build());
              profileManagerRepository.save(
                  ProfileManager.builder()
                      .accountId(account.getId())
                      .profileId(profile.getId())
                      .build());
              profileShareRepository.save(
                  ProfileHouseholdShare.builder()
                      .profileId(profile.getId())
                      .householdId(household.getId())
                      .status(ProfileShareStatus.ACTIVE)
                      .build());
              return new PortableIdentity(account.getId(), household.getId(), profile.getId());
            });
  }

  private record PortableIdentity(UUID accountId, UUID householdId, UUID profileId) {}
}
