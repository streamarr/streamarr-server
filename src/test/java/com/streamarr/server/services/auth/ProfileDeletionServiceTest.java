package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileDeletionMode;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.ProfileDeletionBlockedException;
import com.streamarr.server.fakes.FakeProfileDeletionAuthorizationRepository;
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
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Profile Deletion Service Tests")
class ProfileDeletionServiceTest {

  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeProfileManagerRepository managerRepository = new FakeProfileManagerRepository();
  private final FakeProfileManagerInvitationRepository invitationRepository =
      new FakeProfileManagerInvitationRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeProfileDeletionAuthorizationRepository deletionAuthorizationRepository =
      new FakeProfileDeletionAuthorizationRepository();
  private final PasswordEncoder passwordEncoder =
      PasswordEncoderFactories.createDelegatingPasswordEncoder();
  private final FakeSecurityAuditEventRepository auditRepository =
      new FakeSecurityAuditEventRepository();
  private final SecurityAuditService auditService = new SecurityAuditService(auditRepository);
  private final ProfileDeletionService service =
      new ProfileDeletionService(
          profileRepository,
          managerRepository,
          invitationRepository,
          shareRepository,
          accountRepository,
          deletionAuthorizationRepository,
          passwordEncoder,
          auditService);

  @Test
  @DisplayName("Should block ordinary deletion while profile has a household share")
  void shouldBlockOrdinaryDeletionWhileProfileHasHouseholdShare() {
    var account = saveAccount("correct horse battery staple");
    var profile = profileRepository.save(Profile.builder().name("Shared Profile").build());
    managerRepository.save(
        ProfileManager.builder().accountId(account.getId()).profileId(profile.getId()).build());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(account.getHomeHouseholdId())
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var command =
        DeleteProfileCommand.builder()
            .actingAccountId(account.getId())
            .profileId(profile.getId())
            .password("correct horse battery staple")
            .build();

    assertThatThrownBy(() -> delete(command))
        .isInstanceOf(ProfileDeletionBlockedException.class)
        .hasMessageContaining("shares");

    assertThat(profileRepository.existsById(profile.getId())).isTrue();
  }

  @Test
  @DisplayName("Should delete unshared sole-managed profile after password reauthentication")
  void shouldDeleteUnsharedSoleManagedProfileAfterPasswordReauthentication() {
    var account = saveAccount("correct horse battery staple");
    var profile = profileRepository.save(Profile.builder().name("Ready To Delete").build());
    managerRepository.save(
        ProfileManager.builder().accountId(account.getId()).profileId(profile.getId()).build());

    delete(
        DeleteProfileCommand.builder()
            .actingAccountId(account.getId())
            .profileId(profile.getId())
            .password("correct horse battery staple")
            .build());

    assertThat(profileRepository.existsById(profile.getId())).isFalse();
    assertThat(deletionAuthorizationRepository.findAll())
        .singleElement()
        .satisfies(
            authorization -> {
              assertThat(authorization.getProfileId()).isEqualTo(profile.getId());
              assertThat(authorization.getActingAccountId()).isEqualTo(account.getId());
              assertThat(authorization.getMode()).isEqualTo(ProfileDeletionMode.ORDINARY);
            });
    assertThat(auditRepository.findAll())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getActingAccountId()).isEqualTo(account.getId());
              assertThat(event.getTargetProfileId()).isEqualTo(profile.getId());
              assertThat(event.getOperation()).isEqualTo(SecurityAuditOperation.PROFILE_DELETED);
            });
  }

  @Test
  @DisplayName("Should reject ordinary deletion when password reauthentication fails")
  void shouldRejectOrdinaryDeletionWhenPasswordReauthenticationFails() {
    var account = saveAccount("correct horse battery staple");
    var profile = profileRepository.save(Profile.builder().name("Protected Delete").build());
    managerRepository.save(
        ProfileManager.builder().accountId(account.getId()).profileId(profile.getId()).build());
    var command =
        DeleteProfileCommand.builder()
            .actingAccountId(account.getId())
            .profileId(profile.getId())
            .password("wrong password")
            .build();

    assertThatThrownBy(() -> delete(command)).isInstanceOf(InvalidCredentialsException.class);

    assertThat(profileRepository.existsById(profile.getId())).isTrue();
    assertThat(auditRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should verify deletion password before disclosing blocking profile state")
  void shouldVerifyDeletionPasswordBeforeDisclosingBlockingProfileState() {
    var account = saveAccount("correct horse battery staple");
    var profile =
        profileRepository.save(Profile.builder().name("Protected Shared Profile").build());
    managerRepository.save(
        ProfileManager.builder().accountId(account.getId()).profileId(profile.getId()).build());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(account.getHomeHouseholdId())
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var command =
        DeleteProfileCommand.builder()
            .actingAccountId(account.getId())
            .profileId(profile.getId())
            .password("wrong password")
            .build();

    assertThatThrownBy(() -> delete(command)).isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  @DisplayName("Should block ordinary deletion while manager invitation is pending")
  void shouldBlockOrdinaryDeletionWhileManagerInvitationIsPending() {
    var account = saveAccount("correct horse battery staple");
    var profile = profileRepository.save(Profile.builder().name("Invited Profile").build());
    managerRepository.save(
        ProfileManager.builder().accountId(account.getId()).profileId(profile.getId()).build());
    invitationRepository.save(
        ProfileManagerInvitation.builder()
            .profileId(profile.getId())
            .invitingAccountId(account.getId())
            .invitedAccountId(UUID.randomUUID())
            .status(ProfileManagerInvitationStatus.PENDING)
            .build());
    var command =
        DeleteProfileCommand.builder()
            .actingAccountId(account.getId())
            .profileId(profile.getId())
            .password("correct horse battery staple")
            .build();

    assertThatThrownBy(() -> delete(command))
        .isInstanceOf(ProfileDeletionBlockedException.class)
        .hasMessageContaining("invitations");
  }

  @Test
  @DisplayName("Should block ordinary deletion while another manager remains")
  void shouldBlockOrdinaryDeletionWhileAnotherManagerRemains() {
    var account = saveAccount("correct horse battery staple");
    var profile = profileRepository.save(Profile.builder().name("Co-managed Profile").build());
    managerRepository.save(
        ProfileManager.builder().accountId(account.getId()).profileId(profile.getId()).build());
    managerRepository.save(
        ProfileManager.builder().accountId(UUID.randomUUID()).profileId(profile.getId()).build());
    var command =
        DeleteProfileCommand.builder()
            .actingAccountId(account.getId())
            .profileId(profile.getId())
            .password("correct horse battery staple")
            .build();

    assertThatThrownBy(() -> delete(command))
        .isInstanceOf(ProfileDeletionBlockedException.class)
        .hasMessageContaining("one profile manager");
  }

  private UserAccount saveAccount(String password) {
    return accountRepository.save(
        UserAccount.builder()
            .email("manager-" + UUID.randomUUID() + "@example.com")
            .displayName("Manager")
            .passwordHash(passwordEncoder.encode(password))
            .accountRole(AccountRole.USER)
            .homeHouseholdId(UUID.randomUUID())
            .householdRole(HouseholdRole.OWNER)
            .build());
  }

  private void delete(DeleteProfileCommand command) {
    service.delete(service.prepare(command));
  }
}
