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
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
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
        .isEqualTo(com.streamarr.server.domain.auth.SecurityAuditOperation.PROFILE_SHARE_OFFERED);
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

    assertThatThrownBy(
            () ->
                service.accept(
                    ProfileShareAcceptance.builder()
                        .actingAccountId(parent.getId())
                        .shareId(pendingShare.getId())
                        .build()))
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

    assertThatThrownBy(
            () ->
                service.removeFromHousehold(
                    HouseholdProfileRemoval.builder()
                        .actingAccountId(dad.getId())
                        .shareId(remoteShare.getId())
                        .build()))
        .isInstanceOf(ProfileAccessDeniedException.class);

    assertThat(shareRepository.existsById(remoteShare.getId())).isTrue();
    assertThat(selectionCleaner.clearedSelections).isEmpty();
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
        .isEqualTo(com.streamarr.server.domain.auth.SecurityAuditOperation.PROFILE_SHARE_REJECTED);
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
        .isEqualTo(com.streamarr.server.domain.auth.SecurityAuditOperation.PROFILE_SHARE_CANCELED);
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
