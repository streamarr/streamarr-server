package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KidProfileManagerPolicy {

  // Preflight feedback mirrors the deferred assert_local_kid_manager database invariant.

  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository managerRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final UserAccountRepository accountRepository;

  public void validateManagerRemoval(UUID profileId, UUID removedAccountId) {
    var profile =
        profileRepository.findById(profileId).orElseThrow(ProfileAccessDeniedException::new);
    if (profile.getKind() != ProfileKind.KID) {
      return;
    }

    var householdIds =
        shareRepository.findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE).stream()
            .map(share -> share.getHouseholdId())
            .toList();
    var managers = loadManagers(profileId);
    householdIds.forEach(
        householdId -> requireLocalManager(managers, householdId, removedAccountId));
  }

  public void validateAccountDeparture(UUID accountId, UUID householdId) {
    var managedProfileIds =
        managerRepository.findByAccountId(accountId).stream()
            .map(manager -> manager.getProfileId())
            .toList();

    var activeProfileIds =
        shareRepository.findByHouseholdIdAndStatus(householdId, ProfileShareStatus.ACTIVE).stream()
            .map(share -> share.getProfileId())
            .collect(Collectors.toSet());

    profileRepository.findAllById(managedProfileIds).stream()
        .filter(profile -> profile.getKind() == ProfileKind.KID)
        .filter(profile -> activeProfileIds.contains(profile.getId()))
        .forEach(
            profile -> requireLocalManager(loadManagers(profile.getId()), householdId, accountId));
  }

  public void validateKidKind(UUID profileId) {
    var householdIds =
        shareRepository.findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE).stream()
            .map(share -> share.getHouseholdId())
            .toList();
    if (householdIds.isEmpty()) {
      return;
    }
    var managers = loadManagers(profileId);
    householdIds.forEach(householdId -> requireLocalManager(managers, householdId, null));
  }

  public void validateShareActivation(UUID profileId, UUID householdId) {
    var profile =
        profileRepository.findById(profileId).orElseThrow(ProfileAccessDeniedException::new);
    if (profile.getKind() == ProfileKind.KID) {
      requireLocalManager(loadManagers(profileId), householdId, null);
    }
  }

  private ProfileManagers loadManagers(UUID profileId) {
    var managers = managerRepository.findByProfileId(profileId);
    var accountIds = managers.stream().map(manager -> manager.getAccountId()).toList();
    var accountsById =
        accountRepository.findAllById(accountIds).stream()
            .collect(Collectors.toMap(account -> account.getId(), Function.identity()));
    return new ProfileManagers(managers, accountsById);
  }

  private void requireLocalManager(
      ProfileManagers profileManagers, UUID householdId, UUID excludedAccountId) {
    var hasLocalManager =
        profileManagers.managers().stream()
            .filter(manager -> !manager.getAccountId().equals(excludedAccountId))
            .map(manager -> profileManagers.accountsById().get(manager.getAccountId()))
            .filter(Objects::nonNull)
            .filter(account -> account.isEnabled())
            .anyMatch(
                account ->
                    householdId.equals(account.getHomeHouseholdId())
                        && canManageKid(account.getHouseholdRole()));
    if (!hasLocalManager) {
      throw new KidProfileManagerRequiredException();
    }
  }

  private boolean canManageKid(HouseholdRole role) {
    return role == HouseholdRole.OWNER || role == HouseholdRole.PARENT;
  }

  private record ProfileManagers(
      List<ProfileManager> managers, Map<UUID, UserAccount> accountsById) {}
}
