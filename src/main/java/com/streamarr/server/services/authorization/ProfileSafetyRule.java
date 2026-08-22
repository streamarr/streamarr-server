package com.streamarr.server.services.authorization;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADR 0024 PIN safety, evaluated over the Profiles available in one Household: when any Kid Profile
 * is available, every Adult Profile and every less-restricted Kid Profile available there needs an
 * effective PIN; one without it is locked there. Nothing is stored — selection, refresh, and the
 * picker evaluate the same rule.
 */
public final class ProfileSafetyRule {

  private ProfileSafetyRule() {}

  /** Ids of the Profiles that need an effective PIN in a Household with exactly these available. */
  public static Set<UUID> profilesRequiringPin(Collection<Profile> available) {
    var requiring = new HashSet<UUID>();
    var kids = available.stream().filter(profile -> profile.getKind() == ProfileKind.KID).toList();
    if (kids.isEmpty()) {
      return requiring;
    }

    return available.stream()
        .filter(
            profile ->
                profile.getKind() == ProfileKind.ADULT
                    || isLessRestrictiveThanAnother(profile, kids))
        .map(Profile::getId)
        .collect(Collectors.toCollection(HashSet::new));
  }

  /** Ids of the Profiles locked in a Household with exactly these available. */
  public static Set<UUID> lockedProfiles(Collection<Profile> available) {
    var requiring = profilesRequiringPin(available);
    return available.stream()
        .filter(profile -> requiring.contains(profile.getId()) && !profile.hasEffectivePin())
        .map(Profile::getId)
        .collect(Collectors.toCollection(HashSet::new));
  }

  private static boolean isLessRestrictiveThanAnother(Profile profile, Collection<Profile> kids) {
    return kids.stream()
        .anyMatch(other -> !other.getId().equals(profile.getId()) && isStricter(other, profile));
  }

  /** A ceiling beats no ceiling; a lower ceiling beats a higher one. */
  private static boolean isStricter(Profile stricter, Profile looser) {
    var stricterCeiling = stricter.getMaximumAllowedRatingAge();
    var looserCeiling = looser.getMaximumAllowedRatingAge();
    if (stricterCeiling == null) {
      return false;
    }

    return looserCeiling == null || stricterCeiling < looserCeiling;
  }
}
