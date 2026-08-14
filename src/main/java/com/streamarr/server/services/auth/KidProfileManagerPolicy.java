package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
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
    if (profile.getClassification() != ProfileClassification.KID) {
      return;
    }

    shareRepository.findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE).stream()
        .map(share -> share.getHouseholdId())
        .forEach(householdId -> requireLocalManager(profileId, householdId, removedAccountId));
  }

  public void validateAccountDeparture(UUID accountId, UUID householdId) {
    var managedProfileIds =
        managerRepository.findByAccountId(accountId).stream()
            .map(manager -> manager.getProfileId())
            .toList();

    profileRepository.findAllById(managedProfileIds).stream()
        .filter(profile -> profile.getClassification() == ProfileClassification.KID)
        .filter(
            profile ->
                shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
                    profile.getId(), householdId, ProfileShareStatus.ACTIVE))
        .forEach(profile -> requireLocalManager(profile.getId(), householdId, accountId));
  }

  public void validateKidClassification(UUID profileId) {
    shareRepository.findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE).stream()
        .map(share -> share.getHouseholdId())
        .forEach(householdId -> requireLocalManager(profileId, householdId, null));
  }

  public void validateShareActivation(UUID profileId, UUID householdId) {
    var profile =
        profileRepository.findById(profileId).orElseThrow(ProfileAccessDeniedException::new);
    if (profile.getClassification() == ProfileClassification.KID) {
      requireLocalManager(profileId, householdId, null);
    }
  }

  private void requireLocalManager(UUID profileId, UUID householdId, UUID excludedAccountId) {
    var managers = managerRepository.findByProfileId(profileId);
    var accountIds = managers.stream().map(manager -> manager.getAccountId()).toList();
    var accountsById =
        accountRepository.findAllById(accountIds).stream()
            .collect(Collectors.toMap(account -> account.getId(), Function.identity()));
    var hasLocalManager =
        managers.stream()
            .filter(manager -> !manager.getAccountId().equals(excludedAccountId))
            .map(manager -> accountsById.get(manager.getAccountId()))
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
}
