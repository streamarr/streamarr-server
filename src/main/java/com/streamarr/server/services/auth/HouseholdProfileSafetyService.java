package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.exceptions.ProfileSafetyViolationException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HouseholdProfileSafetyService {

  // Preflight feedback mirrors the deferred assert_household_profile_safety database invariant.

  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileRepository profileRepository;

  public void validateActivation(Profile candidate, UUID householdId) {
    validateHousehold(candidate, householdId);
  }

  public void validatePolicyChange(Profile candidate) {
    var householdIds =
        shareRepository
            .findByProfileIdAndStatus(candidate.getId(), ProfileShareStatus.ACTIVE)
            .stream()
            .map(share -> share.getHouseholdId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (householdIds.isEmpty()) {
      return;
    }

    var shares =
        shareRepository.findByHouseholdIdInAndStatus(householdIds, ProfileShareStatus.ACTIVE);
    var profileIds =
        shares.stream()
            .map(share -> share.getProfileId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    profileIds.add(candidate.getId());
    var profilesById =
        profileRepository.findAllById(profileIds).stream()
            .collect(Collectors.toMap(Profile::getId, Function.identity()));
    profilesById.put(candidate.getId(), candidate);
    var sharesByHousehold =
        shares.stream().collect(Collectors.groupingBy(share -> share.getHouseholdId()));

    householdIds.forEach(
        householdId ->
            validateProfiles(
                profilesFor(sharesByHousehold.getOrDefault(householdId, List.of()), profilesById)));
  }

  private void validateHousehold(Profile candidate, UUID householdId) {
    var profileIds =
        shareRepository.findByHouseholdIdAndStatus(householdId, ProfileShareStatus.ACTIVE).stream()
            .map(share -> share.getProfileId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    profileIds.add(candidate.getId());

    var profilesById =
        profileRepository.findAllById(profileIds).stream()
            .collect(Collectors.toMap(Profile::getId, Function.identity()));
    profilesById.put(candidate.getId(), candidate);
    validateProfiles(profilesFor(profileIds, profilesById));
  }

  private List<Profile> profilesFor(
      List<ProfileHouseholdShare> shares, Map<UUID, Profile> profilesById) {
    return profilesFor(shares.stream().map(share -> share.getProfileId()).toList(), profilesById);
  }

  private List<Profile> profilesFor(Iterable<UUID> profileIds, Map<UUID, Profile> profilesById) {
    var profiles = new LinkedHashSet<Profile>();
    profileIds.forEach(profileId -> profiles.add(profilesById.get(profileId)));
    profiles.remove(null);
    return List.copyOf(profiles);
  }

  private void validateProfiles(List<Profile> profiles) {
    requireUniqueNames(profiles);
    var profilesRequiringPin =
        profiles.stream().filter(profile -> requiresPin(profile, profiles)).toList();
    if (!profilesRequiringPin.isEmpty()) {
      throw new ProfileSafetyViolationException(
          profilesRequiringPin.stream().map(Profile::getId).toList());
    }
  }

  private void requireUniqueNames(List<Profile> profiles) {
    var normalizedNames = new LinkedHashSet<String>();
    var unique =
        profiles.stream()
            .map(Profile::getName)
            .map(name -> name.strip().toLowerCase(Locale.ROOT))
            .allMatch(normalizedNames::add);
    if (!unique) {
      throw new IllegalArgumentException(
          "An active profile name must be unique within a household.");
    }
  }

  private boolean requiresPin(Profile profile, List<Profile> profiles) {
    if (hasEffectivePin(profile)) {
      return false;
    }

    return profiles.stream()
        .filter(candidate -> candidate.getKind() == ProfileKind.KID)
        .anyMatch(kid -> requiresPinAlongside(profile, kid));
  }

  private boolean hasEffectivePin(Profile profile) {
    return profile.getPinHash() != null && !profile.getPinHash().isBlank();
  }

  private boolean requiresPinAlongside(Profile profile, Profile kid) {
    if (profile.getKind() == ProfileKind.ADULT) {
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
