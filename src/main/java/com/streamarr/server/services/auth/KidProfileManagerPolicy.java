package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KidProfileManagerPolicy {

  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository managerRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final UserAccountRepository accountRepository;

  public void validateManagerRemoval(UUID profileId, UUID removedAccountId) {
    var profile = profileRepository.findById(profileId).orElseThrow();
    if (profile.getClassification() != ProfileClassification.KID) {
      return;
    }

    shareRepository.findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE).stream()
        .map(share -> share.getHouseholdId())
        .forEach(householdId -> requireLocalManager(profileId, householdId, removedAccountId));
  }

  public void validateAccountDeparture(UUID accountId, UUID householdId) {
    managerRepository.findByAccountId(accountId).stream()
        .map(manager -> profileRepository.findById(manager.getProfileId()))
        .flatMap(java.util.Optional::stream)
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
    var profile = profileRepository.findById(profileId).orElseThrow();
    if (profile.getClassification() == ProfileClassification.KID) {
      requireLocalManager(profileId, householdId, null);
    }
  }

  private void requireLocalManager(UUID profileId, UUID householdId, UUID excludedAccountId) {
    var hasLocalManager =
        managerRepository.findByProfileId(profileId).stream()
            .filter(manager -> !manager.getAccountId().equals(excludedAccountId))
            .map(manager -> accountRepository.findById(manager.getAccountId()))
            .flatMap(java.util.Optional::stream)
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
