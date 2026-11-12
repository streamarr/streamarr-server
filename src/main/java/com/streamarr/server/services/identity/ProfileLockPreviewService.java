package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileAuthority;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
import com.streamarr.server.domain.auth.ProfileReaderRelation;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfilePolicyTransition;
import com.streamarr.server.services.authorization.ProfileSafetyRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Which Profiles a change of a Profile's kind or Content Ceiling would lock, asked before the
 * change (ADR 0024 §PIN safety). It evaluates {@link ProfileSafetyRule} over each Household where
 * the Profile is available, before and after the change, and always says whether the changed
 * Profile itself would lock. It shows a Household only to a caller who may already see that the
 * Profile is available there, and names a Profile that would lock there only to a caller who may
 * already see that Profile there: one that may use the Household, manages the Profile, or is a
 * ServerAdmin. Of any other it says only that other Profiles would lock. The change itself is never
 * refused for what it locks.
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
   * What giving the Profile the target kind and Content Ceiling would lock in every Household where
   * it is available, as the caller may see it. Empty for a caller who may not make that change, for
   * a negative ceiling, which the change refuses, and for a Profile that does not exist.
   */
  // Not in a read-only transaction: the change's decision holds the Profile's policy with a row
  // lock, as the change itself does.
  public Optional<ProfileLocks> policyChangePreview(
      AuthenticatedIdentity identity, UUID profileId, ProfilePolicyTarget target) {
    if (isNegative(target.maximumAllowedRatingAge())) {
      return Optional.empty();
    }

    var profile = profileRepository.findById(profileId);
    // A decision about a Profile that does not exist fails rather than denies.
    if (profile.isEmpty() || !mayMake(identity, new Intent.SetProfilePolicy(profileId, target))) {
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
    return Optional.of(preview(identity, changed, availableIn, false));
  }

  /**
   * What creating a Profile of the target kind and Content Ceiling in the Household would lock
   * there, the new Profile itself included, before it is created. The caller names the Household,
   * so it is always listed. Empty for a caller who may not create a Profile there, for a negative
   * ceiling, which creation refuses, and for a Household that does not exist.
   */
  // Not in a read-only transaction: creating a Profile is a write, and its decision holds its facts
  // with row locks, as sharePreflight's does.
  public Optional<ProfileLocks> creationPreview(
      AuthenticatedIdentity identity, UUID householdId, ProfilePolicyTarget target) {
    // A decision about a Household that does not exist fails rather than denies.
    if (isNegative(target.maximumAllowedRatingAge())
        || !householdRepository.existsById(householdId)
        || !authorizationService.isAllowed(identity, new Intent.CreateProfile(householdId))) {
      return Optional.empty();
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
    return Optional.of(preview(identity, created, Set.of(householdId), true));
  }

  // A change the caller may make once it reauthenticates afresh is one it may preview.
  private boolean mayMake(AuthenticatedIdentity identity, Intent.SetProfilePolicy change) {
    return switch (authorizationService.decide(identity, change)) {
      case Decision.Allowed<ProfilePolicyTransition> _ -> true;
      case Decision.Denied<ProfilePolicyTransition>(var reason) ->
          reason == Decision.DenialReason.REAUTHENTICATION_REQUIRED;
      case Decision.Failed<ProfilePolicyTransition> _ ->
          throw new AuthorizationUnavailableException();
    };
  }

  private static boolean isNegative(Integer maximumAllowedRatingAge) {
    return maximumAllowedRatingAge != null && maximumAllowedRatingAge < 0;
  }

  private ProfileLocks preview(
      AuthenticatedIdentity identity,
      Profile subject,
      Set<UUID> availableIn,
      boolean callerNamedTheHousehold) {
    var changes =
        availableIn.stream()
            .map(householdId -> changeIn(householdId, subject))
            .flatMap(Optional::stream)
            .toList();
    var sight = new CallerSight(identity, subject, changes);
    var shown = new ArrayList<HouseholdChange>();
    var othersUnnamed = false;
    for (var change : changes) {
      var seen = sight.seenPart(change, callerNamedTheHousehold);
      othersUnnamed |= seen.others().size() < change.others().size();
      if (!seen.others().isEmpty() || seen.locksSubject()) {
        shown.add(seen);
      }
    }

    return new ProfileLocks(
        changes.stream().anyMatch(HouseholdChange::locksSubject),
        householdLocks(identity, shown),
        othersUnnamed);
  }

  private Optional<HouseholdChange> changeIn(UUID householdId, Profile subject) {
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
    return Optional.of(
        new HouseholdChange(householdId, others, newlyLocked.contains(subject.getId())));
  }

  // The caller's own Household first, then the others by name.
  private List<HouseholdLocks> householdLocks(
      AuthenticatedIdentity identity, List<HouseholdChange> shown) {
    var households =
        householdRepository
            .findAllById(shown.stream().map(HouseholdChange::householdId).toList())
            .stream()
            .collect(Collectors.toMap(Household::getId, Function.identity()));
    return shown.stream()
        .filter(change -> households.containsKey(change.householdId()))
        .map(
            change ->
                new HouseholdLocks(
                    households.get(change.householdId()), change.others(), change.locksSubject()))
        .sorted(
            Comparator.comparing(
                    (HouseholdLocks locks) ->
                        !locks.household().getId().equals(identity.householdId()))
                .thenComparing(locks -> locks.household().getName().toLowerCase(Locale.ROOT))
                .thenComparing(locks -> locks.household().getId()))
        .toList();
  }

  /** What one Household would newly lock: the other Profiles, by name, and the subject itself. */
  private record HouseholdChange(UUID householdId, List<Profile> others, boolean locksSubject) {}

  /**
   * What the caller may already see of a change (ADR 0024 §PIN safety): a Profile in a Household
   * where it may use the Household, manages the Profile, or is a ServerAdmin. Each fact beyond the
   * Households it may use is read once, only when a change needs it.
   */
  private final class CallerSight {

    private final AuthenticatedIdentity identity;
    private final UUID subjectId;
    private final Set<UUID> profileIds;
    private Set<UUID> usable;
    private Boolean serverAdmin;
    private Set<UUID> managed;

    private CallerSight(
        AuthenticatedIdentity identity, Profile subject, List<HouseholdChange> changes) {
      this.identity = identity;
      this.subjectId = subject.getId();
      this.profileIds =
          Stream.concat(
                  Stream.of(subjectId),
                  changes.stream().flatMap(change -> change.others().stream()).map(Profile::getId))
              .collect(Collectors.toSet());
    }

    /**
     * The part of the change the caller may see: nothing in a Household where it may not see that
     * the subject is available, which it always sees in a Household it named itself.
     */
    HouseholdChange seenPart(HouseholdChange change, boolean namedByTheCaller) {
      if (!namedByTheCaller && !sees(change.householdId(), subjectId)) {
        return new HouseholdChange(change.householdId(), List.of(), false);
      }

      return new HouseholdChange(
          change.householdId(),
          change.others().stream()
              .filter(other -> sees(change.householdId(), other.getId()))
              .toList(),
          change.locksSubject());
    }

    private boolean sees(UUID householdId, UUID profileId) {
      return usable().contains(householdId) || serverAdmin() || managed().contains(profileId);
    }

    private Set<UUID> usable() {
      if (usable == null) {
        usable = Set.copyOf(userAccountRepository.findUsableHouseholdIds(identity.accountId()));
      }

      return usable;
    }

    private boolean serverAdmin() {
      if (serverAdmin == null) {
        serverAdmin = authorizationService.isAllowed(identity, new Intent.ViewHouseholds());
      }

      return serverAdmin;
    }

    private Set<UUID> managed() {
      if (managed == null) {
        managed =
            profileRepository.relationsTo(identity.accountId(), profileIds).stream()
                .filter(
                    relation ->
                        relation.authority().filter(ProfileAuthority.MANAGES::equals).isPresent())
                .map(ProfileReaderRelation::profileId)
                .collect(Collectors.toSet());
      }

      return managed;
    }
  }
}
