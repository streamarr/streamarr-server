package com.streamarr.server.services.auth;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountIdentity;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
import com.streamarr.server.exceptions.ProfileManagementDeniedException;
import com.streamarr.server.exceptions.ProfileManagerInvariantException;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile Management Service Tests")
class ProfileManagementServiceTest {

  private final FakeProfileManagerRepository managerRepository = new FakeProfileManagerRepository();
  private final FakeProfileManagerInvitationRepository invitationRepository =
      new FakeProfileManagerInvitationRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeSecurityAuditEventRepository auditRepository =
      new FakeSecurityAuditEventRepository();
  private final ProfileManagementService service =
      new ProfileManagementService(
          managerRepository,
          invitationRepository,
          new KidProfileManagerPolicy(
              profileRepository, managerRepository, shareRepository, accountRepository),
          new SecurityAuditService(auditRepository),
          accountRepository,
          profileRepository,
          shareRepository,
          new HouseholdProfileSafetyService(shareRepository, profileRepository));

  @Test
  @DisplayName("Should create portable profile with manager and active home share atomically")
  void shouldCreatePortableProfileWithManagerAndActiveHomeShareAtomically() {
    var householdId = UUID.randomUUID();
    var creator = saveAccount(householdId, HouseholdRole.OWNER);

    var profile =
        service.create(
            CreatePortableProfileCommand.builder()
                .authority(accountIdentity(creator))
                .name("Portable Profile")
                .kind(ProfileKind.ADULT)
                .maximumAllowedRatingAge(16)
                .pinHash("encoded-pin")
                .build());

    assertThat(profileRepository.findById(profile.getId())).contains(profile);
    assertThat(managerRepository.existsByAccountIdAndProfileId(creator.getId(), profile.getId()))
        .isTrue();
    assertThat(
            shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
                profile.getId(), householdId, ProfileShareStatus.ACTIVE))
        .isTrue();
    assertThat(auditRepository.findAll())
        .extracting(event -> event.getOperation())
        .containsExactly(SecurityAuditOperation.PROFILE_CREATED);
  }

  @Test
  @DisplayName("Should create portable profile in signed household after account state changes")
  void shouldCreatePortableProfileInSignedHouseholdAfterAccountStateChanges() {
    var signedHouseholdId = UUID.randomUUID();
    var liveHouseholdId = UUID.randomUUID();
    var creator = saveAccount(liveHouseholdId, HouseholdRole.OWNER);

    var profile =
        service.create(
            CreatePortableProfileCommand.builder()
                .authority(identity(creator, signedHouseholdId, HouseholdRole.MEMBER))
                .name("Signed Home Profile")
                .kind(ProfileKind.ADULT)
                .build());

    assertThat(
            shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
                profile.getId(), signedHouseholdId, ProfileShareStatus.ACTIVE))
        .isTrue();
    assertThat(
            shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
                profile.getId(), liveHouseholdId, ProfileShareStatus.ACTIVE))
        .isFalse();
  }

  @Test
  @DisplayName("Should let named manager rename portable profile globally")
  void shouldLetNamedManagerRenamePortableProfileGlobally() {
    var managerId = UUID.randomUUID();
    var profile = profileRepository.save(Profile.builder().name("Before").build());
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(profile.getId()).build());

    service.rename(
        RenamePortableProfileCommand.builder()
            .actingAccountId(managerId)
            .profileId(profile.getId())
            .name("After")
            .build());

    assertThat(profileRepository.findById(profile.getId()).orElseThrow().getName())
        .isEqualTo("After");
    assertThat(auditRepository.findAll())
        .extracting(event -> event.getOperation())
        .containsExactly(SecurityAuditOperation.PROFILE_RENAMED);
  }

  @Test
  @DisplayName("Should reject missing or blank portable profile name")
  void shouldRejectMissingOrBlankPortableProfileName() {
    var accountId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    var blankName =
        RenamePortableProfileCommand.builder()
            .actingAccountId(accountId)
            .profileId(profileId)
            .name("  ")
            .build();
    var invalidCreation =
        CreatePortableProfileCommand.builder()
            .authority(accountIdentityBuilder().accountId(accountId).build())
            .kind(ProfileKind.ADULT);

    assertThatThrownBy(invalidCreation::build).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.rename(blankName))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject portable profile rename by non-manager")
  void shouldRejectPortableProfileRenameByNonManager() {
    var command =
        RenamePortableProfileCommand.builder()
            .actingAccountId(UUID.randomUUID())
            .profileId(UUID.randomUUID())
            .name("Denied")
            .build();

    assertThatThrownBy(() -> service.rename(command))
        .isInstanceOf(ProfileManagementDeniedException.class);
  }

  @Test
  @DisplayName("Should create manager only when named invitee personally accepts")
  void shouldCreateManagerOnlyWhenNamedInviteePersonallyAccepts() {
    var currentManagerId = UUID.randomUUID();
    var inviteeId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    saveAccount(inviteeId);
    managerRepository.save(
        ProfileManager.builder().accountId(currentManagerId).profileId(profileId).build());

    var invitation =
        service.invite(
            ProfileManagerInvite.builder()
                .actingAccountId(currentManagerId)
                .invitedAccountId(inviteeId)
                .profileId(profileId)
                .build());

    assertThat(invitation.getStatus()).isEqualTo(ProfileManagerInvitationStatus.PENDING);
    assertThat(managerRepository.existsByAccountIdAndProfileId(inviteeId, profileId)).isFalse();

    service.accept(
        ProfileManagerInvitationAcceptance.builder()
            .actingAccountId(inviteeId)
            .invitationId(invitation.getId())
            .build());

    assertThat(managerRepository.existsByAccountIdAndProfileId(inviteeId, profileId)).isTrue();
    assertThat(invitation.getStatus()).isEqualTo(ProfileManagerInvitationStatus.ACCEPTED);
    assertThat(auditRepository.findAll())
        .extracting(event -> event.getOperation())
        .containsExactlyInAnyOrder(
            SecurityAuditOperation.PROFILE_MANAGER_INVITED,
            SecurityAuditOperation.PROFILE_MANAGER_ACCEPTED);
  }

  @Test
  @DisplayName("Should not audit a repeated pending profile manager invitation")
  void shouldNotAuditRepeatedPendingProfileManagerInvitation() {
    var currentManagerId = UUID.randomUUID();
    var inviteeId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    saveAccount(inviteeId);
    managerRepository.save(
        ProfileManager.builder().accountId(currentManagerId).profileId(profileId).build());
    var invite =
        ProfileManagerInvite.builder()
            .actingAccountId(currentManagerId)
            .invitedAccountId(inviteeId)
            .profileId(profileId)
            .build();

    var first = service.invite(invite);
    var second = service.invite(invite);

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(auditRepository.findAll())
        .extracting(event -> event.getOperation())
        .containsExactly(SecurityAuditOperation.PROFILE_MANAGER_INVITED);
  }

  @Test
  @DisplayName("Should reject profile manager self invitation")
  void shouldRejectProfileManagerSelfInvitation() {
    var managerId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(profileId).build());
    var invitation =
        ProfileManagerInvite.builder()
            .actingAccountId(managerId)
            .invitedAccountId(managerId)
            .profileId(profileId)
            .build();

    assertThatThrownBy(() -> service.invite(invitation))
        .isInstanceOf(ProfileManagementDeniedException.class);
  }

  @Test
  @DisplayName("Should reject invitation for existing profile manager")
  void shouldRejectInvitationForExistingProfileManager() {
    var invitingManagerId = UUID.randomUUID();
    var existingManagerId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    managerRepository.save(
        ProfileManager.builder().accountId(invitingManagerId).profileId(profileId).build());
    managerRepository.save(
        ProfileManager.builder().accountId(existingManagerId).profileId(profileId).build());
    var invitation =
        ProfileManagerInvite.builder()
            .actingAccountId(invitingManagerId)
            .invitedAccountId(existingManagerId)
            .profileId(profileId)
            .build();

    assertThatThrownBy(() -> service.invite(invitation))
        .isInstanceOf(ProfileManagementDeniedException.class);
  }

  @Test
  @DisplayName("Should reject invitation for nonexistent account")
  void shouldRejectInvitationForNonexistentAccount() {
    var invitingManagerId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    managerRepository.save(
        ProfileManager.builder().accountId(invitingManagerId).profileId(profileId).build());
    var invitation =
        ProfileManagerInvite.builder()
            .actingAccountId(invitingManagerId)
            .invitedAccountId(UUID.randomUUID())
            .profileId(profileId)
            .build();

    assertThatThrownBy(() -> service.invite(invitation))
        .isInstanceOf(ProfileManagementDeniedException.class);
  }

  @Test
  @DisplayName("Should let named invitee reject pending manager invitation")
  void shouldLetNamedInviteeRejectPendingManagerInvitation() {
    var currentManagerId = UUID.randomUUID();
    var inviteeId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    saveAccount(inviteeId);
    managerRepository.save(
        ProfileManager.builder().accountId(currentManagerId).profileId(profileId).build());
    var invitation =
        service.invite(
            ProfileManagerInvite.builder()
                .actingAccountId(currentManagerId)
                .invitedAccountId(inviteeId)
                .profileId(profileId)
                .build());

    service.reject(
        ProfileManagerInvitationRejection.builder()
            .actingAccountId(inviteeId)
            .invitationId(invitation.getId())
            .build());

    assertThat(invitation.getStatus()).isEqualTo(ProfileManagerInvitationStatus.REJECTED);
    assertThat(managerRepository.existsByAccountIdAndProfileId(inviteeId, profileId)).isFalse();
    assertThat(auditRepository.findAll())
        .extracting(event -> event.getOperation())
        .containsExactlyInAnyOrder(
            SecurityAuditOperation.PROFILE_MANAGER_INVITED,
            SecurityAuditOperation.PROFILE_MANAGER_INVITATION_REJECTED);
  }

  @Test
  @DisplayName("Should reject stale or mismatched manager invitation rejection")
  void shouldRejectStaleOrMismatchedManagerInvitationRejection() {
    var invitedAccountId = UUID.randomUUID();
    var stale =
        invitationRepository.save(
            ProfileManagerInvitation.builder()
                .profileId(UUID.randomUUID())
                .invitedAccountId(invitedAccountId)
                .status(ProfileManagerInvitationStatus.ACCEPTED)
                .build());
    var pending =
        invitationRepository.save(
            ProfileManagerInvitation.builder()
                .profileId(UUID.randomUUID())
                .invitedAccountId(invitedAccountId)
                .status(ProfileManagerInvitationStatus.PENDING)
                .build());
    var staleRejection =
        ProfileManagerInvitationRejection.builder()
            .actingAccountId(invitedAccountId)
            .invitationId(stale.getId())
            .build();
    var mismatchedRejection =
        ProfileManagerInvitationRejection.builder()
            .actingAccountId(UUID.randomUUID())
            .invitationId(pending.getId())
            .build();

    assertThatThrownBy(() -> service.reject(staleRejection))
        .isInstanceOf(ProfileManagementDeniedException.class);
    assertThatThrownBy(() -> service.reject(mismatchedRejection))
        .isInstanceOf(ProfileManagementDeniedException.class);
  }

  @Test
  @DisplayName("Should let profile manager cancel pending manager invitation")
  void shouldLetProfileManagerCancelPendingManagerInvitation() {
    var currentManagerId = UUID.randomUUID();
    var inviteeId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    saveAccount(inviteeId);
    managerRepository.save(
        ProfileManager.builder().accountId(currentManagerId).profileId(profileId).build());
    var invitation =
        service.invite(
            ProfileManagerInvite.builder()
                .actingAccountId(currentManagerId)
                .invitedAccountId(inviteeId)
                .profileId(profileId)
                .build());

    service.cancel(
        ProfileManagerInvitationCancellation.builder()
            .actingAccountId(currentManagerId)
            .invitationId(invitation.getId())
            .build());

    assertThat(invitation.getStatus()).isEqualTo(ProfileManagerInvitationStatus.CANCELED);
    assertThat(auditRepository.findAll())
        .extracting(event -> event.getOperation())
        .containsExactlyInAnyOrder(
            SecurityAuditOperation.PROFILE_MANAGER_INVITED,
            SecurityAuditOperation.PROFILE_MANAGER_INVITATION_CANCELED);
  }

  @Test
  @DisplayName("Should reject canceling completed manager invitation")
  void shouldRejectCancelingCompletedManagerInvitation() {
    var invitation =
        invitationRepository.save(
            ProfileManagerInvitation.builder()
                .profileId(UUID.randomUUID())
                .invitedAccountId(UUID.randomUUID())
                .status(ProfileManagerInvitationStatus.ACCEPTED)
                .build());
    var cancellation =
        ProfileManagerInvitationCancellation.builder()
            .actingAccountId(UUID.randomUUID())
            .invitationId(invitation.getId())
            .build();

    assertThatThrownBy(() -> service.cancel(cancellation))
        .isInstanceOf(ProfileManagementDeniedException.class);
  }

  @Test
  @DisplayName("Should reject accepting completed or mismatched manager invitation")
  void shouldRejectAcceptingCompletedOrMismatchedManagerInvitation() {
    var invitedAccountId = UUID.randomUUID();
    var completed =
        invitationRepository.save(
            ProfileManagerInvitation.builder()
                .profileId(UUID.randomUUID())
                .invitedAccountId(invitedAccountId)
                .status(ProfileManagerInvitationStatus.ACCEPTED)
                .build());
    var pending =
        invitationRepository.save(
            ProfileManagerInvitation.builder()
                .profileId(UUID.randomUUID())
                .invitedAccountId(invitedAccountId)
                .status(ProfileManagerInvitationStatus.PENDING)
                .build());
    var completedAcceptance =
        ProfileManagerInvitationAcceptance.builder()
            .actingAccountId(invitedAccountId)
            .invitationId(completed.getId())
            .build();
    var mismatchedAcceptance =
        ProfileManagerInvitationAcceptance.builder()
            .actingAccountId(UUID.randomUUID())
            .invitationId(pending.getId())
            .build();

    assertThatThrownBy(() -> service.accept(completedAcceptance))
        .isInstanceOf(ProfileManagementDeniedException.class);
    assertThatThrownBy(() -> service.accept(mismatchedAcceptance))
        .isInstanceOf(ProfileManagementDeniedException.class);
  }

  @Test
  @DisplayName("Should reject relinquishment when actor is the sole manager")
  void shouldRejectRelinquishmentWhenActorIsSoleManager() {
    var managerId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(profileId).build());
    var relinquishment =
        ProfileManagementRelinquishment.builder()
            .actingAccountId(managerId)
            .profileId(profileId)
            .build();

    assertThatThrownBy(() -> service.relinquish(relinquishment))
        .isInstanceOf(ProfileManagerInvariantException.class)
        .hasMessageContaining("at least one manager");

    assertThat(managerRepository.existsByAccountIdAndProfileId(managerId, profileId)).isTrue();
  }

  @Test
  @DisplayName("Should reject relinquishment by the last local parent manager of active kid")
  void shouldRejectRelinquishmentByLastLocalParentManagerOfActiveKid() {
    var householdId = UUID.randomUUID();
    var local = saveAccount(householdId, HouseholdRole.PARENT);
    var remote = saveAccount(UUID.randomUUID(), HouseholdRole.PARENT);
    var kid =
        profileRepository.save(
            Profile.builder()
                .name("Portable Kid")
                .kind(ProfileKind.KID)
                .maximumAllowedRatingAge(7)
                .build());
    managerRepository.save(
        ProfileManager.builder().accountId(local.getId()).profileId(kid.getId()).build());
    managerRepository.save(
        ProfileManager.builder().accountId(remote.getId()).profileId(kid.getId()).build());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(kid.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var relinquishment =
        ProfileManagementRelinquishment.builder()
            .actingAccountId(local.getId())
            .profileId(kid.getId())
            .build();

    assertThatThrownBy(() -> service.relinquish(relinquishment))
        .isInstanceOf(KidProfileManagerRequiredException.class);

    assertThat(managerRepository.existsByAccountIdAndProfileId(local.getId(), kid.getId()))
        .isTrue();
  }

  @Test
  @DisplayName("Should let one of multiple adult profile managers relinquish management")
  void shouldLetOneOfMultipleAdultProfileManagersRelinquishManagement() {
    var profile =
        profileRepository.save(Profile.builder().name("Adult").kind(ProfileKind.ADULT).build());
    var departingManagerId = UUID.randomUUID();
    managerRepository.save(
        ProfileManager.builder().accountId(departingManagerId).profileId(profile.getId()).build());
    managerRepository.save(
        ProfileManager.builder().accountId(UUID.randomUUID()).profileId(profile.getId()).build());

    service.relinquish(
        ProfileManagementRelinquishment.builder()
            .actingAccountId(departingManagerId)
            .profileId(profile.getId())
            .build());

    assertThat(managerRepository.existsByAccountIdAndProfileId(departingManagerId, profile.getId()))
        .isFalse();
    assertThat(auditRepository.findAll())
        .extracting(event -> event.getOperation())
        .containsExactly(SecurityAuditOperation.PROFILE_MANAGER_RELINQUISHED);
  }

  private UserAccount saveAccount(UUID householdId, HouseholdRole role) {
    return accountRepository.save(
        UserAccount.builder()
            .email("manager-" + UUID.randomUUID() + "@example.com")
            .displayName("Manager")
            .passwordHash("encoded")
            .accountRole(AccountRole.USER)
            .homeHouseholdId(householdId)
            .householdRole(role)
            .build());
  }

  private AuthenticatedIdentity identity(
      UserAccount account, UUID householdId, HouseholdRole householdRole) {
    return AuthenticatedIdentity.builder()
        .accountId(account.getId())
        .role(account.getAccountRole())
        .authSessionId(UUID.randomUUID())
        .scope(TokenScope.ACCOUNT)
        .householdId(householdId)
        .householdRole(householdRole)
        .build();
  }

  private void saveAccount(UUID accountId) {
    accountRepository.save(
        UserAccount.builder()
            .id(accountId)
            .email("invitee-" + accountId + "@example.com")
            .displayName("Invitee")
            .passwordHash("encoded")
            .accountRole(AccountRole.USER)
            .homeHouseholdId(UUID.randomUUID())
            .householdRole(HouseholdRole.MEMBER)
            .enabled(true)
            .build());
  }
}
