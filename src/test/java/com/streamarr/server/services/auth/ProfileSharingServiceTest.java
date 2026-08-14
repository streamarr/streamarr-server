package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.ProfileManagementDeniedException;
import com.streamarr.server.exceptions.ProfileSafetyViolationException;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeProfileSelectionCleaner;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile Sharing Service Tests")
class ProfileSharingServiceTest {

  private final FakeProfileManagerRepository managerRepository = new FakeProfileManagerRepository();
  private final FakeProfileManagerInvitationRepository invitationRepository =
      new FakeProfileManagerInvitationRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeSecurityAuditEventRepository auditRepository =
      new FakeSecurityAuditEventRepository();
  private final SecurityAuditService auditService = new SecurityAuditService(auditRepository);
  private final KidProfileManagerPolicy kidManagerPolicy =
      new KidProfileManagerPolicy(
          profileRepository, managerRepository, shareRepository, accountRepository);
  private final HouseholdProfileSafetyService safetyService =
      new HouseholdProfileSafetyService(shareRepository, profileRepository);
  private final ProfileManagementService managementService =
      new ProfileManagementService(
          managerRepository,
          invitationRepository,
          kidManagerPolicy,
          auditService,
          accountRepository,
          profileRepository,
          shareRepository,
          safetyService);
  private final FakeProfileSelectionCleaner selectionCleaner = new FakeProfileSelectionCleaner();

  private final ProfileSharingService service =
      new ProfileSharingService(
          managerRepository,
          shareRepository,
          accountRepository,
          profileRepository,
          managementService,
          safetyService,
          selectionCleaner,
          kidManagerPolicy,
          auditService);

  @Test
  @DisplayName("Should create pending share when profile manager offers profile")
  void shouldCreatePendingShareWhenProfileManagerOffersProfile() {
    var managerId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    var householdId = UUID.randomUUID();
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(profileId).build());

    var offeredShare =
        service.offer(
            ProfileShareOffer.builder()
                .actingAccountId(managerId)
                .profileId(profileId)
                .targetHouseholdId(householdId)
                .build());

    assertThat(offeredShare.getProfileId()).isEqualTo(profileId);
    assertThat(offeredShare.getHouseholdId()).isEqualTo(householdId);
    assertThat(offeredShare.getStatus()).isEqualTo(ProfileShareStatus.PENDING);
    assertThat(shareRepository.findAll()).containsExactly(offeredShare);
    assertThat(auditRepository.findAll())
        .singleElement()
        .extracting(event -> event.getOperation())
        .isEqualTo(SecurityAuditOperation.PROFILE_SHARE_OFFERED);
  }

  @Test
  @DisplayName("Should reject share offer from account that does not manage profile")
  void shouldRejectShareOfferFromAccountThatDoesNotManageProfile() {
    var offer =
        ProfileShareOffer.builder()
            .actingAccountId(UUID.randomUUID())
            .profileId(UUID.randomUUID())
            .targetHouseholdId(UUID.randomUUID())
            .build();

    assertThatThrownBy(() -> service.offer(offer))
        .isInstanceOf(ProfileManagementDeniedException.class);
    assertThat(shareRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should activate adult share when target parent accepts")
  void shouldActivateAdultShareWhenTargetParentAccepts() {
    var householdId = UUID.randomUUID();
    var parent = saveAccount(householdId, HouseholdRole.PARENT);
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Visiting Adult")
                .classification(ProfileClassification.ADULT)
                .build());
    var pendingShare =
        shareRepository.save(
            ProfileHouseholdShare.builder()
                .profileId(profile.getId())
                .householdId(householdId)
                .status(ProfileShareStatus.PENDING)
                .build());

    var acceptedShare =
        service.accept(
            ProfileShareAcceptance.builder()
                .actingAccountId(parent.getId())
                .shareId(pendingShare.getId())
                .build());

    assertThat(acceptedShare.getStatus()).isEqualTo(ProfileShareStatus.ACTIVE);
    assertThat(managerRepository.existsByAccountIdAndProfileId(parent.getId(), profile.getId()))
        .isFalse();
  }

  @Test
  @DisplayName("Should accept attached management invitation with adult profile share")
  void shouldAcceptAttachedManagementInvitationWithAdultProfileShare() {
    var householdId = UUID.randomUUID();
    var parent = saveAccount(householdId, HouseholdRole.PARENT);
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Co-managed Adult")
                .classification(ProfileClassification.ADULT)
                .build());
    var pendingShare = saveShare(profile.getId(), householdId, ProfileShareStatus.PENDING);
    var invitation =
        invitationRepository.save(
            ProfileManagerInvitation.builder()
                .profileId(profile.getId())
                .invitingAccountId(UUID.randomUUID())
                .invitedAccountId(parent.getId())
                .status(ProfileManagerInvitationStatus.PENDING)
                .build());

    service.accept(
        ProfileShareAcceptance.builder()
            .actingAccountId(parent.getId())
            .shareId(pendingShare.getId())
            .managementInvitationId(invitation.getId())
            .build());

    assertThat(managerRepository.existsByAccountIdAndProfileId(parent.getId(), profile.getId()))
        .isTrue();
    assertThat(invitation.getStatus()).isEqualTo(ProfileManagerInvitationStatus.ACCEPTED);
  }

  @Test
  @DisplayName("Should reject share acceptance from another household")
  void shouldRejectShareAcceptanceFromAnotherHousehold() {
    var parent = saveAccount(UUID.randomUUID(), HouseholdRole.PARENT);
    var pending = saveShare(UUID.randomUUID(), UUID.randomUUID(), ProfileShareStatus.PENDING);
    var acceptance =
        ProfileShareAcceptance.builder()
            .actingAccountId(parent.getId())
            .shareId(pending.getId())
            .build();

    assertThatThrownBy(() -> service.accept(acceptance))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should reject share acceptance by household member")
  void shouldRejectShareAcceptanceByHouseholdMember() {
    var householdId = UUID.randomUUID();
    var member = saveAccount(householdId, HouseholdRole.MEMBER);
    var pending = saveShare(UUID.randomUUID(), householdId, ProfileShareStatus.PENDING);
    var acceptance =
        ProfileShareAcceptance.builder()
            .actingAccountId(member.getId())
            .shareId(pending.getId())
            .build();

    assertThatThrownBy(() -> service.accept(acceptance))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should reject acceptance when share is already active")
  void shouldRejectAcceptanceWhenShareIsAlreadyActive() {
    var householdId = UUID.randomUUID();
    var parent = saveAccount(householdId, HouseholdRole.PARENT);
    var active = saveShare(UUID.randomUUID(), householdId, ProfileShareStatus.ACTIVE);
    var acceptance =
        ProfileShareAcceptance.builder()
            .actingAccountId(parent.getId())
            .shareId(active.getId())
            .build();

    assertThatThrownBy(() -> service.accept(acceptance))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName(
      "Should reject kid share when a household receiving the profile has no local parent manager")
  void shouldRejectKidShareWhenReceivingHouseholdHasNoLocalParentManager() {
    var householdId = UUID.randomUUID();
    var parent = saveAccount(householdId, HouseholdRole.PARENT);
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Portable Kid")
                .classification(ProfileClassification.KID)
                .maximumAllowedRatingAge(7)
                .build());
    var pendingShare =
        shareRepository.save(
            ProfileHouseholdShare.builder()
                .profileId(profile.getId())
                .householdId(householdId)
                .status(ProfileShareStatus.PENDING)
                .build());
    var acceptance =
        ProfileShareAcceptance.builder()
            .actingAccountId(parent.getId())
            .shareId(pendingShare.getId())
            .build();

    assertThatThrownBy(() -> service.accept(acceptance))
        .isInstanceOf(KidProfileManagerRequiredException.class);

    assertThat(pendingShare.getStatus()).isEqualTo(ProfileShareStatus.PENDING);
  }

  @Test
  @DisplayName("Should atomically accept kid management invitation with target share")
  void shouldAtomicallyAcceptKidManagementInvitationWithTargetShare() {
    var householdId = UUID.randomUUID();
    var parent = saveAccount(householdId, HouseholdRole.PARENT);
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Portable Kid")
                .classification(ProfileClassification.KID)
                .maximumAllowedRatingAge(7)
                .build());
    var pendingShare =
        shareRepository.save(
            ProfileHouseholdShare.builder()
                .profileId(profile.getId())
                .householdId(householdId)
                .status(ProfileShareStatus.PENDING)
                .build());
    var invitation =
        invitationRepository.save(
            ProfileManagerInvitation.builder()
                .profileId(profile.getId())
                .invitingAccountId(UUID.randomUUID())
                .invitedAccountId(parent.getId())
                .status(ProfileManagerInvitationStatus.PENDING)
                .build());

    var acceptedShare =
        service.accept(
            ProfileShareAcceptance.builder()
                .actingAccountId(parent.getId())
                .shareId(pendingShare.getId())
                .managementInvitationId(invitation.getId())
                .build());

    assertThat(acceptedShare.getStatus()).isEqualTo(ProfileShareStatus.ACTIVE);
    assertThat(managerRepository.existsByAccountIdAndProfileId(parent.getId(), profile.getId()))
        .isTrue();
    assertThat(invitation.getStatus()).isEqualTo(ProfileManagerInvitationStatus.ACCEPTED);
  }

  @Test
  @DisplayName("Should reject kid share when management invitation names another profile")
  void shouldRejectKidShareWhenManagementInvitationNamesAnotherProfile() {
    var householdId = UUID.randomUUID();
    var parent = saveAccount(householdId, HouseholdRole.PARENT);
    var kid =
        profileRepository.save(
            Profile.builder()
                .name("Portable Kid")
                .classification(ProfileClassification.KID)
                .maximumAllowedRatingAge(7)
                .build());
    var pendingShare = saveShare(kid.getId(), householdId, ProfileShareStatus.PENDING);
    var otherProfileInvitation =
        invitationRepository.save(
            ProfileManagerInvitation.builder()
                .profileId(UUID.randomUUID())
                .invitingAccountId(UUID.randomUUID())
                .invitedAccountId(parent.getId())
                .status(ProfileManagerInvitationStatus.PENDING)
                .build());
    var acceptance =
        ProfileShareAcceptance.builder()
            .actingAccountId(parent.getId())
            .shareId(pendingShare.getId())
            .managementInvitationId(otherProfileInvitation.getId())
            .build();

    assertThatThrownBy(() -> service.accept(acceptance))
        .isInstanceOf(ProfileManagementDeniedException.class);
    assertThat(otherProfileInvitation.getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.PENDING);
    assertThat(pendingShare.getStatus()).isEqualTo(ProfileShareStatus.PENDING);
  }

  @Test
  @DisplayName("Should reject kid share when target adult profile has no PIN")
  void shouldRejectKidShareWhenTargetAdultProfileHasNoPin() {
    var householdId = UUID.randomUUID();
    var parent = saveAccount(householdId, HouseholdRole.PARENT);
    var kid =
        profileRepository.save(
            Profile.builder()
                .name("Portable Kid")
                .classification(ProfileClassification.KID)
                .maximumAllowedRatingAge(7)
                .build());
    var adult =
        profileRepository.save(
            Profile.builder()
                .name("Unprotected Adult")
                .classification(ProfileClassification.ADULT)
                .build());
    managerRepository.save(
        ProfileManager.builder().accountId(parent.getId()).profileId(kid.getId()).build());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(adult.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var pendingKidShare =
        shareRepository.save(
            ProfileHouseholdShare.builder()
                .profileId(kid.getId())
                .householdId(householdId)
                .status(ProfileShareStatus.PENDING)
                .build());

    assertThatThrownBy(
            () ->
                service.accept(
                    ProfileShareAcceptance.builder()
                        .actingAccountId(parent.getId())
                        .shareId(pendingKidShare.getId())
                        .build()))
        .isInstanceOfSatisfying(
            ProfileSafetyViolationException.class,
            exception ->
                assertThat(exception.profilesRequiringPin()).containsExactly(adult.getId()));

    assertThat(pendingKidShare.getStatus()).isEqualTo(ProfileShareStatus.PENDING);
  }

  @Test
  @DisplayName("Should reject household parent removing profile from another household")
  void shouldRejectHouseholdParentRemovingProfileFromAnotherHousehold() {
    var dad = saveAccount(UUID.randomUUID(), HouseholdRole.PARENT);
    var momHouseholdId = UUID.randomUUID();
    var remoteShare =
        shareRepository.save(
            ProfileHouseholdShare.builder()
                .profileId(UUID.randomUUID())
                .householdId(momHouseholdId)
                .status(ProfileShareStatus.ACTIVE)
                .build());
    var removal =
        HouseholdProfileRemoval.builder()
            .actingAccountId(dad.getId())
            .shareId(remoteShare.getId())
            .build();

    assertThatThrownBy(() -> service.removeFromHousehold(removal))
        .isInstanceOf(ProfileAccessDeniedException.class);

    assertThat(shareRepository.existsById(remoteShare.getId())).isTrue();
    assertThat(selectionCleaner.clearedSelections).isEmpty();
  }

  @Test
  @DisplayName("Should let household owner remove active shared profile")
  void shouldLetHouseholdOwnerRemoveActiveSharedProfile() {
    var householdId = UUID.randomUUID();
    var owner = saveAccount(householdId, HouseholdRole.OWNER);
    var active = saveShare(UUID.randomUUID(), householdId, ProfileShareStatus.ACTIVE);

    service.removeFromHousehold(
        HouseholdProfileRemoval.builder()
            .actingAccountId(owner.getId())
            .shareId(active.getId())
            .build());

    assertThat(shareRepository.existsById(active.getId())).isFalse();
    assertThat(selectionCleaner.clearedSelections)
        .containsExactly(
            new FakeProfileSelectionCleaner.ClearedSelection(active.getProfileId(), householdId));
    assertThat(auditRepository.findAll())
        .singleElement()
        .extracting(event -> event.getOperation())
        .isEqualTo(SecurityAuditOperation.PROFILE_UNSHARED_BY_HOUSEHOLD);
  }

  @Test
  @DisplayName("Should reject household member removing active shared profile")
  void shouldRejectHouseholdMemberRemovingActiveSharedProfile() {
    var householdId = UUID.randomUUID();
    var member = saveAccount(householdId, HouseholdRole.MEMBER);
    var active = saveShare(UUID.randomUUID(), householdId, ProfileShareStatus.ACTIVE);
    var removal =
        HouseholdProfileRemoval.builder()
            .actingAccountId(member.getId())
            .shareId(active.getId())
            .build();

    assertThatThrownBy(() -> service.removeFromHousehold(removal))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should reject household parent removing pending shared profile")
  void shouldRejectHouseholdParentRemovingPendingSharedProfile() {
    var householdId = UUID.randomUUID();
    var parent = saveAccount(householdId, HouseholdRole.PARENT);
    var pending = saveShare(UUID.randomUUID(), householdId, ProfileShareStatus.PENDING);
    var removal =
        HouseholdProfileRemoval.builder()
            .actingAccountId(parent.getId())
            .shareId(pending.getId())
            .build();

    assertThatThrownBy(() -> service.removeFromHousehold(removal))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should let target household parent reject a pending share")
  void shouldLetTargetHouseholdParentRejectPendingShare() {
    var householdId = UUID.randomUUID();
    var parent = saveAccount(householdId, HouseholdRole.PARENT);
    var pending =
        shareRepository.save(
            ProfileHouseholdShare.builder()
                .profileId(UUID.randomUUID())
                .householdId(householdId)
                .status(ProfileShareStatus.PENDING)
                .build());

    service.reject(
        ProfileShareRejection.builder()
            .actingAccountId(parent.getId())
            .shareId(pending.getId())
            .build());

    assertThat(shareRepository.existsById(pending.getId())).isFalse();
    assertThat(auditRepository.findAll())
        .singleElement()
        .extracting(event -> event.getOperation())
        .isEqualTo(SecurityAuditOperation.PROFILE_SHARE_REJECTED);
  }

  @Test
  @DisplayName("Should reject an already active share rejection")
  void shouldRejectAlreadyActiveShareRejection() {
    var householdId = UUID.randomUUID();
    var parent = saveAccount(householdId, HouseholdRole.PARENT);
    var active = saveShare(UUID.randomUUID(), householdId, ProfileShareStatus.ACTIVE);
    var rejection =
        ProfileShareRejection.builder()
            .actingAccountId(parent.getId())
            .shareId(active.getId())
            .build();

    assertThatThrownBy(() -> service.reject(rejection))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should reject pending share rejection from another household")
  void shouldRejectPendingShareRejectionFromAnotherHousehold() {
    var parent = saveAccount(UUID.randomUUID(), HouseholdRole.PARENT);
    var pending = saveShare(UUID.randomUUID(), UUID.randomUUID(), ProfileShareStatus.PENDING);
    var rejection =
        ProfileShareRejection.builder()
            .actingAccountId(parent.getId())
            .shareId(pending.getId())
            .build();

    assertThatThrownBy(() -> service.reject(rejection))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should reject pending share rejection by household member")
  void shouldRejectPendingShareRejectionByHouseholdMember() {
    var householdId = UUID.randomUUID();
    var member = saveAccount(householdId, HouseholdRole.MEMBER);
    var pending = saveShare(UUID.randomUUID(), householdId, ProfileShareStatus.PENDING);
    var rejection =
        ProfileShareRejection.builder()
            .actingAccountId(member.getId())
            .shareId(pending.getId())
            .build();

    assertThatThrownBy(() -> service.reject(rejection))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should let profile manager cancel a pending share")
  void shouldLetProfileManagerCancelPendingShare() {
    var managerId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(profileId).build());
    var pending =
        shareRepository.save(
            ProfileHouseholdShare.builder()
                .profileId(profileId)
                .householdId(UUID.randomUUID())
                .status(ProfileShareStatus.PENDING)
                .build());

    service.cancel(
        ProfileShareCancellation.builder()
            .actingAccountId(managerId)
            .shareId(pending.getId())
            .build());

    assertThat(shareRepository.existsById(pending.getId())).isFalse();
    assertThat(auditRepository.findAll())
        .singleElement()
        .extracting(event -> event.getOperation())
        .isEqualTo(SecurityAuditOperation.PROFILE_SHARE_CANCELED);
  }

  @Test
  @DisplayName("Should reject canceling active share")
  void shouldRejectCancelingActiveShare() {
    var profileId = UUID.randomUUID();
    var managerId = UUID.randomUUID();
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(profileId).build());
    var active = saveShare(profileId, UUID.randomUUID(), ProfileShareStatus.ACTIVE);
    var cancellation =
        ProfileShareCancellation.builder()
            .actingAccountId(managerId)
            .shareId(active.getId())
            .build();

    assertThatThrownBy(() -> service.cancel(cancellation))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should reject canceling pending share by non-manager")
  void shouldRejectCancelingPendingShareByNonManager() {
    var pending = saveShare(UUID.randomUUID(), UUID.randomUUID(), ProfileShareStatus.PENDING);
    var cancellation =
        ProfileShareCancellation.builder()
            .actingAccountId(UUID.randomUUID())
            .shareId(pending.getId())
            .build();

    assertThatThrownBy(() -> service.cancel(cancellation))
        .isInstanceOf(ProfileManagementDeniedException.class);
  }

  @Test
  @DisplayName("Should let active co-managed kid profile leave only account current home")
  void shouldLetActiveCoManagedKidProfileLeaveOnlyAccountCurrentHome() {
    var householdId = UUID.randomUUID();
    var account = saveAccount(householdId, HouseholdRole.MEMBER);
    var kid =
        profileRepository.save(
            Profile.builder()
                .name("Co-managed Kid")
                .classification(ProfileClassification.KID)
                .maximumAllowedRatingAge(7)
                .build());
    managerRepository.save(
        ProfileManager.builder().accountId(UUID.randomUUID()).profileId(kid.getId()).build());
    managerRepository.save(
        ProfileManager.builder().accountId(UUID.randomUUID()).profileId(kid.getId()).build());
    var currentShare =
        shareRepository.save(
            ProfileHouseholdShare.builder()
                .profileId(kid.getId())
                .householdId(householdId)
                .status(ProfileShareStatus.ACTIVE)
                .build());

    service.leaveCurrentHome(
        ProfileHomeDeparture.builder()
            .actingAccountId(account.getId())
            .activeProfileId(kid.getId())
            .build());

    assertThat(shareRepository.existsById(currentShare.getId())).isFalse();
    assertThat(selectionCleaner.clearedSelections)
        .containsExactly(
            new FakeProfileSelectionCleaner.ClearedSelection(kid.getId(), householdId));
  }

  @Test
  @DisplayName("Should reject leaving home when profile share is pending")
  void shouldRejectLeavingHomeWhenProfileShareIsPending() {
    var householdId = UUID.randomUUID();
    var account = saveAccount(householdId, HouseholdRole.MEMBER);
    var pending = saveShare(UUID.randomUUID(), householdId, ProfileShareStatus.PENDING);
    var departure =
        ProfileHomeDeparture.builder()
            .actingAccountId(account.getId())
            .activeProfileId(pending.getProfileId())
            .build();

    assertThatThrownBy(() -> service.leaveCurrentHome(departure))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  private ProfileHouseholdShare saveShare(
      UUID profileId, UUID householdId, ProfileShareStatus status) {
    return shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(status)
            .build());
  }

  private UserAccount saveAccount(UUID homeHouseholdId, HouseholdRole householdRole) {
    return accountRepository.save(
        UserAccount.builder()
            .email("viewer-" + UUID.randomUUID() + "@example.com")
            .displayName("Viewer")
            .passwordHash("{noop}not-a-real-hash")
            .accountRole(AccountRole.USER)
            .homeHouseholdId(homeHouseholdId)
            .householdRole(householdRole)
            .build());
  }
}
