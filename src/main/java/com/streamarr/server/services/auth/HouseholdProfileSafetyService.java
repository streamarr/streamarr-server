package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.exceptions.ProfileSafetyViolationException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HouseholdProfileSafetyService {

  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileRepository profileRepository;

  public void validateActivation(Profile candidate, UUID householdId) {
    validateHousehold(candidate, householdId);
  }

  public void validatePolicyChange(Profile candidate) {
    shareRepository.findByProfileIdAndStatus(candidate.getId(), ProfileShareStatus.ACTIVE).stream()
        .map(share -> share.getHouseholdId())
        .distinct()
        .forEach(householdId -> validateHousehold(candidate, householdId));
  }

  private void validateHousehold(Profile candidate, UUID householdId) {
    var profileIds =
        shareRepository.findByHouseholdIdAndStatus(householdId, ProfileShareStatus.ACTIVE).stream()
            .map(share -> share.getProfileId())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    profileIds.add(candidate.getId());

    var profiles =
        profileRepository.findAllById(profileIds).stream()
            .map(profile -> profile.getId().equals(candidate.getId()) ? candidate : profile)
            .toList();
    var profilesRequiringPin =
        profiles.stream().filter(profile -> requiresPin(profile, profiles)).toList();
    if (!profilesRequiringPin.isEmpty()) {
      throw new ProfileSafetyViolationException(
          profilesRequiringPin.stream().map(Profile::getId).toList());
    }
  }

  private boolean requiresPin(Profile profile, List<Profile> profiles) {
    if (hasEffectivePin(profile)) {
      return false;
    }

    return profiles.stream()
        .filter(candidate -> candidate.getClassification() == ProfileClassification.KID)
        .anyMatch(kid -> requiresPinAlongside(profile, kid));
  }

  private boolean hasEffectivePin(Profile profile) {
    return profile.getPinHash() != null && !profile.getPinHash().isBlank();
  }

  private boolean requiresPinAlongside(Profile profile, Profile kid) {
    if (profile.getClassification() == ProfileClassification.ADULT) {
      return true;
    }

    return isLessRestricted(profile.getMaximumAllowedRatingAge(), kid.getMaximumAllowedRatingAge());
  }

  private boolean isLessRestricted(Integer profileCeiling, Integer kidCeiling) {
    if (profileCeiling == null) {
      return kidCeiling != null;
    }
    return kidCeiling != null && profileCeiling > kidCeiling;
  }
}
