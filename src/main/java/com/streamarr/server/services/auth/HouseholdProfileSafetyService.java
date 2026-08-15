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

  /**
   * Validates a candidate profile against the active profiles shared with a household.
   *
   * @param candidate   the profile to validate
   * @param householdId the household whose shared profiles are included
   */
  public void validateActivation(Profile candidate, UUID householdId) {
    validateHousehold(candidate, householdId);
  }

  /**
   * Validates an updated profile against the active profiles in each household where it is shared.
   *
   * @param candidate the updated profile to validate
   */
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

  /**
   * Validates the candidate profile together with the household's active shared profiles.
   *
   * @param candidate   the profile to validate
   * @param householdId the household whose active shared profiles are included
   */
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

  /**
   * Builds an ordered list of unique profiles for the specified identifiers.
   *
   * @param profileIds   the profile identifiers to resolve
   * @param profilesById the profiles indexed by identifier
   * @return the resolved profiles, excluding identifiers without a matching profile
   */
  private List<Profile> profilesFor(Iterable<UUID> profileIds, Map<UUID, Profile> profilesById) {
    var profiles = new LinkedHashSet<Profile>();
    profileIds.forEach(profileId -> profiles.add(profilesById.get(profileId)));
    profiles.remove(null);
    return List.copyOf(profiles);
  }

  /**
   * Validates that profiles requiring a PIN have one configured.
   *
   * @param profiles profiles to validate
   * @throws ProfileSafetyViolationException if one or more profiles require a PIN but do not have one
   */
  private void validateProfiles(List<Profile> profiles) {
    var profilesRequiringPin =
        profiles.stream().filter(profile -> requiresPin(profile, profiles)).toList();
    if (!profilesRequiringPin.isEmpty()) {
      throw new ProfileSafetyViolationException(
          profilesRequiringPin.stream().map(Profile::getId).toList());
    }
  }

  /**
   * Determines whether a profile must have a PIN based on the child profiles in the household.
   *
   * @param profile  the profile being evaluated
   * @param profiles the profiles in the household
   * @return {@code true} if the profile has no effective PIN and must have one, {@code false} otherwise
   */
  private boolean requiresPin(Profile profile, List<Profile> profiles) {
    if (hasEffectivePin(profile)) {
      return false;
    }

    return profiles.stream()
        .filter(candidate -> candidate.getKind() == ProfileKind.KID)
        .anyMatch(kid -> requiresPinAlongside(profile, kid));
  }

  /**
   * Determines whether a profile has a non-null, nonblank PIN hash.
   *
   * @param profile the profile to inspect
   * @return {@code true} if the profile has a non-null, nonblank PIN hash, {@code false} otherwise
   */
  private boolean hasEffectivePin(Profile profile) {
    return profile.getPinHash() != null && !profile.getPinHash().isBlank();
  }

  /**
   * Determines whether a profile must have a PIN when sharing a household with a child profile.
   *
   * @param profile the profile being evaluated
   * @param kid     the child profile used for comparison
   * @return {@code true} if the profile is an adult or has a less restrictive rating ceiling than the child, {@code false} otherwise
   */
  private boolean requiresPinAlongside(Profile profile, Profile kid) {
    if (profile.getKind() == ProfileKind.ADULT) {
      return true;
    }

    return isLessRestricted(profile.getMaximumAllowedRatingAge(), kid.getMaximumAllowedRatingAge());
  }

  /**
   * Determines whether a profile's rating ceiling is less restrictive than a child's ceiling.
   *
   * @param profileCeiling the profile's allowed rating ceiling
   * @param kidCeiling the child's allowed rating ceiling
   * @return {@code true} if the profile ceiling is less restrictive, {@code false} otherwise
   */
  private boolean isLessRestricted(Integer profileCeiling, Integer kidCeiling) {
    if (profileCeiling == null) {
      return kidCeiling != null;
    }
    return kidCeiling != null && profileCeiling > kidCeiling;
  }
}
