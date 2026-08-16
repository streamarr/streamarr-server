package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PortableIdentityQueryService {

  private final UserAccountRepository accountRepository;
  private final ProfileManagerRepository managerRepository;
  private final ProfileManagerInvitationRepository invitationRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileRepository profileRepository;
  private final HouseholdRepository householdRepository;

  @Transactional(readOnly = true)
  public List<ProfileShareView> shares(AuthenticatedIdentity identity) {
    var managedProfileIds = managedProfileIds(identity.accountId());
    var managedShares = shareRepository.findByProfileIdIn(managedProfileIds);
    List<ProfileHouseholdShare> shares;
    if (!identity.hasHouseholdRole(identity.householdId(), HouseholdRole.PARENT)) {
      shares = sortedShares(managedShares.stream());
    } else {
      shares =
          sortedShares(
              Stream.concat(
                  managedShares.stream(),
                  shareRepository.findByHouseholdId(identity.householdId()).stream()));
    }
    var profiles =
        profileRepository
            .findAllById(shares.stream().map(ProfileHouseholdShare::getProfileId).toList())
            .stream()
            .collect(Collectors.toMap(Profile::getId, Function.identity()));
    var households =
        householdRepository
            .findAllById(shares.stream().map(ProfileHouseholdShare::getHouseholdId).toList())
            .stream()
            .collect(Collectors.toMap(Household::getId, Function.identity()));
    return shares.stream()
        .map(
            share ->
                new ProfileShareView(
                    share,
                    require(profiles, share.getProfileId()),
                    require(households, share.getHouseholdId())))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<ProfileManagerInvitationView> invitations(AuthenticatedIdentity identity) {
    var invitations =
        Stream.concat(
                invitationRepository.findByInvitedAccountId(identity.accountId()).stream(),
                invitationRepository
                    .findByProfileIdIn(managedProfileIds(identity.accountId()))
                    .stream())
            .distinct()
            .sorted(Comparator.comparing(ProfileManagerInvitation::getId))
            .toList();
    var profiles =
        profilesById(invitations.stream().map(ProfileManagerInvitation::getProfileId).toList());
    var accountIds =
        invitations.stream()
            .flatMap(
                invitation ->
                    Stream.of(invitation.getInvitingAccountId(), invitation.getInvitedAccountId()))
            .toList();
    var accounts = accountsById(accountIds);
    return invitations.stream()
        .map(
            invitation ->
                new ProfileManagerInvitationView(
                    invitation,
                    require(profiles, invitation.getProfileId()),
                    require(accounts, invitation.getInvitingAccountId()),
                    require(accounts, invitation.getInvitedAccountId())))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<ProfileManagerView> managers(AuthenticatedIdentity identity) {
    var accessibleProfileIds = managedProfileIds(identity.accountId());
    if (identity.hasHouseholdRole(identity.householdId(), HouseholdRole.PARENT)) {
      shareRepository.findByHouseholdId(identity.householdId()).stream()
          .map(ProfileHouseholdShare::getProfileId)
          .forEach(accessibleProfileIds::add);
    }
    var managers =
        managerRepository.findByProfileIdIn(accessibleProfileIds).stream()
            .sorted(Comparator.comparing(ProfileManager::getId))
            .toList();
    var profiles = profilesById(managers.stream().map(ProfileManager::getProfileId).toList());
    var accounts = accountsById(managers.stream().map(ProfileManager::getAccountId).toList());
    return managers.stream()
        .map(
            manager ->
                new ProfileManagerView(
                    manager,
                    require(profiles, manager.getProfileId()),
                    require(accounts, manager.getAccountId())))
        .toList();
  }

  private Set<UUID> managedProfileIds(UUID accountId) {
    return new HashSet<>(
        managerRepository.findByAccountId(accountId).stream()
            .map(ProfileManager::getProfileId)
            .toList());
  }

  private List<ProfileHouseholdShare> sortedShares(Stream<ProfileHouseholdShare> shares) {
    return shares.distinct().sorted(Comparator.comparing(ProfileHouseholdShare::getId)).toList();
  }

  private <T> T require(Map<UUID, T> values, UUID id) {
    return Objects.requireNonNull(values.get(id));
  }

  private Map<UUID, Profile> profilesById(Collection<UUID> profileIds) {
    return profileRepository.findAllById(profileIds.stream().distinct().toList()).stream()
        .collect(Collectors.toMap(Profile::getId, Function.identity()));
  }

  private Map<UUID, UserAccount> accountsById(Collection<UUID> accountIds) {
    return accountRepository.findAllById(accountIds.stream().distinct().toList()).stream()
        .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
  }

  public record ProfileShareView(
      ProfileHouseholdShare share, Profile profile, Household household) {}

  public record ProfileManagerInvitationView(
      ProfileManagerInvitation invitation,
      Profile profile,
      UserAccount invitingAccount,
      UserAccount invitedAccount) {}

  public record ProfileManagerView(ProfileManager manager, Profile profile, UserAccount account) {}
}
