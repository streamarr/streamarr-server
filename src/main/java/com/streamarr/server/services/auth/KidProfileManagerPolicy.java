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

  /**
   * Validates that removing an account from a kid profile leaves each shared household with a local manager.
   *
   * @param profileId        the profile whose manager is being removed
   * @param removedAccountId the account being removed as a manager
   * @throws ProfileAccessDeniedException     if the profile does not exist
   * @throws KidProfileManagerRequiredException if a shared household would have no qualifying local manager
   */
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

  /**
   * Validates that an account can leave a household without leaving shared kid profiles
   * without a qualifying local manager.
   *
   * @param accountId  the departing account
   * @param householdId the household affected by the departure
   */
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

  /**
   * Validates that every household actively sharing a kid profile has an enabled local owner or parent manager.
   *
   * @param profileId the profile to validate
   */
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

  /**
   * Validates whether a profile can be activated for sharing with a household.
   *
   * @param profileId   the profile to validate
   * @param householdId the household receiving the share
   */
  public void validateShareActivation(UUID profileId, UUID householdId) {
    var profile =
        profileRepository.findById(profileId).orElseThrow(ProfileAccessDeniedException::new);
    if (profile.getKind() == ProfileKind.KID) {
      requireLocalManager(loadManagers(profileId), householdId, null);
    }
  }

  /**
   * Loads the managers and corresponding user accounts for a profile.
   *
   * @param profileId the profile whose managers are loaded
   * @return the profile managers and accounts indexed by account ID
   */
  private ProfileManagers loadManagers(UUID profileId) {
    var managers = managerRepository.findByProfileId(profileId);
    var accountIds = managers.stream().map(manager -> manager.getAccountId()).toList();
    var accountsById =
        accountRepository.findAllById(accountIds).stream()
            .collect(Collectors.toMap(account -> account.getId(), Function.identity()));
    return new ProfileManagers(managers, accountsById);
  }

  /**
   * Ensures the household retains an enabled local account authorized to manage kid profiles.
   *
   * @param profileManagers   the profile managers and their associated accounts
   * @param householdId       the household that must retain a qualifying manager
   * @param excludedAccountId the account excluded from consideration
   * @throws KidProfileManagerRequiredException if no qualifying local manager exists
   */
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

  /**
   * Determines whether a household role authorizes management of kid profiles.
   *
   * @param role the household role to evaluate
   * @return {@code true} for the {@code OWNER} or {@code PARENT} role, {@code false} otherwise
   */
  private boolean canManageKid(HouseholdRole role) {
    return role == HouseholdRole.OWNER || role == HouseholdRole.PARENT;
  }

  private record ProfileManagers(
      List<ProfileManager> managers, Map<UUID, UserAccount> accountsById) {}
}
