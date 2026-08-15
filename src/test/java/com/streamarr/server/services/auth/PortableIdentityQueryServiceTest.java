package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
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
  private final PortableIdentityQueryService service =
      new PortableIdentityQueryService(
          accountRepository, managerRepository, invitationRepository, shareRepository);

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
        .extracting(ProfileHouseholdShare::getId)
        .containsExactlyInAnyOrder(managedShare.getId(), householdShare.getId());
    assertThat(service.invitations(identity))
        .extracting(ProfileManagerInvitation::getId)
        .containsExactlyInAnyOrder(outgoingInvitation.getId(), receivedInvitation.getId());
    assertThat(service.managers(identity))
        .extracting(ProfileManager::getId)
        .containsExactlyInAnyOrder(
            managerRepository.findByAccountId(account.getId()).getFirst().getId(),
            managedProfileManager.getId(),
            householdProfileManager.getId());
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
    return ProfileManager.builder().accountId(accountId).profileId(profileId).build();
  }

  private ProfileHouseholdShare saveShare(UUID profileId, UUID householdId) {
    return shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(ProfileShareStatus.PENDING)
            .build());
  }

  private ProfileManagerInvitation saveInvitation(
      UUID profileId, UUID invitingAccountId, UUID invitedAccountId) {
    return invitationRepository.save(
        ProfileManagerInvitation.builder()
            .profileId(profileId)
            .invitingAccountId(invitingAccountId)
            .invitedAccountId(invitedAccountId)
            .status(ProfileManagerInvitationStatus.PENDING)
            .build());
  }

  private AuthenticatedIdentity identity(UUID accountId) {
    return AuthenticatedIdentity.builder()
        .accountId(accountId)
        .role(AccountRole.USER)
        .authSessionId(UUID.randomUUID())
        .scope(TokenScope.ACCOUNT)
        .build();
  }
}
