package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Portable Identity Query Service Tests")
class PortableIdentityQueryServiceTest {

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeProfileManagerRepository managerRepository = new FakeProfileManagerRepository();
  private final FakeProfileManagerInvitationRepository invitationRepository =
      new FakeProfileManagerInvitationRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeHouseholdRepository householdRepository = new FakeHouseholdRepository();
  private final PortableIdentityQueryService service =
      new PortableIdentityQueryService(
          accountRepository,
          managerRepository,
          invitationRepository,
          shareRepository,
          profileRepository,
          householdRepository);

  @Test
  @DisplayName("Should expose only portable identity administration records the account can act on")
  void shouldExposeOnlyPortableIdentityAdministrationRecordsAccountCanActOn() {
    var householdId = UUID.randomUUID();
    var account = saveAccount(householdId, HouseholdRole.PARENT);
    var managedProfileId = UUID.randomUUID();
    var householdProfileId = UUID.randomUUID();
    var hiddenProfileId = UUID.randomUUID();
    managerRepository.save(manager(account.getId(), managedProfileId));
    var managedShare = saveShare(managedProfileId, UUID.randomUUID());
    var householdShare = saveShare(householdProfileId, householdId);
    saveShare(hiddenProfileId, UUID.randomUUID());
    var outgoingInvitation = saveInvitation(managedProfileId, account.getId(), UUID.randomUUID());
    var receivedInvitation = saveInvitation(UUID.randomUUID(), UUID.randomUUID(), account.getId());
    saveInvitation(hiddenProfileId, UUID.randomUUID(), UUID.randomUUID());
    var managedProfileManager =
        managerRepository.save(manager(UUID.randomUUID(), managedProfileId));
    var householdProfileManager =
        managerRepository.save(manager(UUID.randomUUID(), householdProfileId));
    managerRepository.save(manager(UUID.randomUUID(), hiddenProfileId));

    var identity = identity(account.getId());

    assertThat(service.shares(identity))
        .extracting(view -> view.share().getId())
        .containsExactlyInAnyOrder(managedShare.getId(), householdShare.getId());
    assertThat(service.invitations(identity))
        .extracting(view -> view.invitation().getId())
        .containsExactlyInAnyOrder(outgoingInvitation.getId(), receivedInvitation.getId());
    assertThat(service.managers(identity))
        .extracting(view -> view.manager().getId())
        .containsExactlyInAnyOrder(
            managerRepository.findByAccountId(account.getId()).getFirst().getId(),
            managedProfileManager.getId(),
            householdProfileManager.getId());

    var shareView =
        service.shares(identity).stream()
            .filter(view -> view.share().getId().equals(householdShare.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(shareView.profile().getName()).isEqualTo("Profile " + householdProfileId);
    assertThat(shareView.household().getName()).isEqualTo("Household " + householdId);

    var invitationView =
        service.invitations(identity).stream()
            .filter(view -> view.invitation().getId().equals(outgoingInvitation.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(invitationView.profile().getName()).isEqualTo("Profile " + managedProfileId);
    assertThat(invitationView.invitingAccount().getDisplayName())
        .isEqualTo("Portable Query Account");
    assertThat(invitationView.invitedAccount().getDisplayName())
        .isEqualTo("Manager " + outgoingInvitation.getInvitedAccountId());

    var managerView =
        service.managers(identity).stream()
            .filter(view -> view.manager().getId().equals(managedProfileManager.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(managerView.profile().getName()).isEqualTo("Profile " + managedProfileId);
    assertThat(managerView.account().getDisplayName())
        .isEqualTo("Manager " + managedProfileManager.getAccountId());
  }

  @Test
  @DisplayName("Should hide current household administration records from older signed authority")
  void shouldHideCurrentHouseholdAdministrationRecordsFromOlderSignedAuthority() {
    var signedHouseholdId = UUID.randomUUID();
    var liveHouseholdId = UUID.randomUUID();
    var account = saveAccount(liveHouseholdId, HouseholdRole.OWNER);
    var liveProfileId = UUID.randomUUID();
    var liveShare = saveShare(liveProfileId, liveHouseholdId);
    var liveManager = managerRepository.save(manager(UUID.randomUUID(), liveProfileId));
    var identity = identity(account.getId(), signedHouseholdId, HouseholdRole.MEMBER);

    assertThat(service.shares(identity))
        .extracting(view -> view.share().getId())
        .doesNotContain(liveShare.getId());
    assertThat(service.managers(identity))
        .extracting(view -> view.manager().getId())
        .doesNotContain(liveManager.getId());
  }

  private UserAccount saveAccount(UUID householdId, HouseholdRole role) {
    return accountRepository.save(
        UserAccount.builder()
            .email("portable-query-" + UUID.randomUUID() + "@example.com")
            .displayName("Portable Query Account")
            .passwordHash("encoded")
            .accountRole(AccountRole.USER)
            .homeHouseholdId(householdId)
            .householdRole(role)
            .build());
  }

  private ProfileManager manager(UUID accountId, UUID profileId) {
    ensureProfile(profileId);
    if (!accountRepository.existsById(accountId)) {
      accountRepository.save(
          UserAccount.builder()
              .id(accountId)
              .email("manager-" + accountId + "@example.com")
              .displayName("Manager " + accountId)
              .passwordHash("encoded")
              .accountRole(AccountRole.USER)
              .homeHouseholdId(UUID.randomUUID())
              .householdRole(HouseholdRole.MEMBER)
              .build());
    }
    return ProfileManager.builder().accountId(accountId).profileId(profileId).build();
  }

  private ProfileHouseholdShare saveShare(UUID profileId, UUID householdId) {
    ensureProfile(profileId);
    if (!householdRepository.existsById(householdId)) {
      householdRepository.save(
          Household.builder().id(householdId).name("Household " + householdId).build());
    }
    return shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(ProfileShareStatus.PENDING)
            .build());
  }

  private ProfileManagerInvitation saveInvitation(
      UUID profileId, UUID invitingAccountId, UUID invitedAccountId) {
    ensureProfile(profileId);
    manager(invitingAccountId, profileId);
    manager(invitedAccountId, profileId);
    return invitationRepository.save(
        ProfileManagerInvitation.builder()
            .profileId(profileId)
            .invitingAccountId(invitingAccountId)
            .invitedAccountId(invitedAccountId)
            .status(ProfileManagerInvitationStatus.PENDING)
            .build());
  }

  private void ensureProfile(UUID profileId) {
    if (!profileRepository.existsById(profileId)) {
      profileRepository.save(
          Profile.builder()
              .id(profileId)
              .name("Profile " + profileId)
              .kind(ProfileKind.ADULT)
              .build());
    }
  }

  private AuthenticatedIdentity identity(UUID accountId) {
    var account = accountRepository.findById(accountId).orElseThrow();
    return identity(accountId, account.getHomeHouseholdId(), account.getHouseholdRole());
  }

  private AuthenticatedIdentity identity(
      UUID accountId, UUID householdId, HouseholdRole householdRole) {
    return AuthenticatedIdentity.builder()
        .accountId(accountId)
        .role(AccountRole.USER)
        .authSessionId(UUID.randomUUID())
        .scope(TokenScope.ACCOUNT)
        .householdId(householdId)
        .householdRole(householdRole)
        .build();
  }
}
