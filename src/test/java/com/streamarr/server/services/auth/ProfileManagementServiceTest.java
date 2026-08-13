package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
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
                .actingAccountId(creator.getId())
                .name("Portable Profile")
                .classification(ProfileClassification.ADULT)
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
  @DisplayName("Should create manager only when named invitee personally accepts")
  void shouldCreateManagerOnlyWhenNamedInviteePersonallyAccepts() {
    var currentManagerId = UUID.randomUUID();
    var inviteeId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
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
  @DisplayName("Should let named invitee reject pending manager invitation")
  void shouldLetNamedInviteeRejectPendingManagerInvitation() {
    var currentManagerId = UUID.randomUUID();
    var inviteeId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
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
  @DisplayName("Should let profile manager cancel pending manager invitation")
  void shouldLetProfileManagerCancelPendingManagerInvitation() {
    var currentManagerId = UUID.randomUUID();
    var inviteeId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
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
  @DisplayName("Should reject relinquishment when actor is the sole manager")
  void shouldRejectRelinquishmentWhenActorIsSoleManager() {
    var managerId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(profileId).build());

    assertThatThrownBy(
            () ->
                service.relinquish(
                    ProfileManagementRelinquishment.builder()
                        .actingAccountId(managerId)
                        .profileId(profileId)
                        .build()))
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
                .classification(ProfileClassification.KID)
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

    assertThatThrownBy(
            () ->
                service.relinquish(
                    ProfileManagementRelinquishment.builder()
                        .actingAccountId(local.getId())
                        .profileId(kid.getId())
                        .build()))
        .isInstanceOf(KidProfileManagerRequiredException.class);

    assertThat(managerRepository.existsByAccountIdAndProfileId(local.getId(), kid.getId()))
        .isTrue();
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
}
