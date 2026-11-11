package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfileSafetyRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which Profiles a change of a Profile's kind or content limit would lock, asked before the change
 * (ADR 0024 §PIN safety). It evaluates {@link ProfileSafetyRule} over a Household's available
 * Profiles before and after the change, and names only the Households whose Profile picker the
 * caller may already see: those it may use. The change itself is never refused for what it locks.
 */
@Service
@RequiredArgsConstructor
public class ProfileLockPreviewService {

  private static final Comparator<Profile> BY_NAME =
      Comparator.comparing((Profile profile) -> profile.getName().toLowerCase(Locale.ROOT))
          .thenComparing(Profile::getId);

  private final AuthorizationService authorizationService;
  private final ProfileRepository profileRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final UserAccountRepository userAccountRepository;
  private final HouseholdRepository householdRepository;

  /**
   * What giving the Profile the target kind and content limit would lock, in each Household where
   * it is available and the caller may use, membership Household first. Empty for a caller who may
   * not read the Profile's administration and for a Profile that does not exist.
   */
  @Transactional(readOnly = true)
  public Optional<List<HouseholdLocks>> policyChangePreview(
      AuthenticatedIdentity identity, UUID profileId, ProfilePolicyTarget target) {
    var profile = profileRepository.findById(profileId);
    // A decision about a Profile that does not exist fails rather than denies.
    if (profile.isEmpty()
        || !authorizationService.isAllowed(
            identity, new Intent.ViewProfileAdministration(profileId))) {
      return Optional.empty();
    }

    // A detached copy: the preview never writes the change.
    var changed =
        profile.get().toBuilder()
            .kind(target.kind())
            .maximumAllowedRatingAge(target.maximumAllowedRatingAge())
            .build();
    var availableIn =
        shareRepository.findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE).stream()
            .map(share -> share.getHouseholdId())
            .collect(Collectors.toSet());
    var locks = new ArrayList<HouseholdLocks>();
    for (var householdId : userAccountRepository.findUsableHouseholdIds(identity.accountId())) {
      if (availableIn.contains(householdId)) {
        locksIn(householdId, changed).ifPresent(locks::add);
      }
    }

    return Optional.of(List.copyOf(locks));
  }

  /**
   * What creating a Profile of the target kind and content limit in the Household would lock there,
   * the new Profile itself included, before it is created. Lists no Household when the caller may
   * not use it. Empty for a caller who may not create a Profile there and for a Household that does
   * not exist.
   */
  // Not in a read-only transaction: creating a Profile is a write, and its decision holds its facts
  // with row locks, as sharePreflight's does.
  public Optional<List<HouseholdLocks>> creationPreview(
      AuthenticatedIdentity identity, UUID householdId, ProfilePolicyTarget target) {
    // A decision about a Household that does not exist fails rather than denies.
    if (!householdRepository.existsById(householdId)
        || !authorizationService.isAllowed(identity, new Intent.CreateProfile(householdId))) {
      return Optional.empty();
    }

    if (!userAccountRepository.mayUseHousehold(identity.accountId(), householdId)) {
      return Optional.of(List.of());
    }

    // Never saved: it stands for the Profile that creation would add.
    var created =
        Profile.builder()
            .id(UUID.randomUUID())
            .householdId(householdId)
            .name("")
            .kind(target.kind())
            .maximumAllowedRatingAge(target.maximumAllowedRatingAge())
            .build();
    return Optional.of(locksIn(householdId, created).stream().toList());
  }

  private Optional<HouseholdLocks> locksIn(UUID householdId, Profile subject) {
    var before = profileRepository.findAvailableInHousehold(householdId);
    var after = new ArrayList<>(before);
    after.removeIf(other -> other.getId().equals(subject.getId()));
    after.add(subject);
    var newlyLocked = ProfileSafetyRule.lockedProfiles(after);
    newlyLocked.removeAll(ProfileSafetyRule.lockedProfiles(before));
    if (newlyLocked.isEmpty()) {
      return Optional.empty();
    }

    var others =
        before.stream()
            .filter(other -> !other.getId().equals(subject.getId()))
            .filter(other -> newlyLocked.contains(other.getId()))
            .sorted(BY_NAME)
            .toList();
    return householdRepository
        .findById(householdId)
        .map(
            household ->
                new HouseholdLocks(household, others, newlyLocked.contains(subject.getId())));
  }
}
