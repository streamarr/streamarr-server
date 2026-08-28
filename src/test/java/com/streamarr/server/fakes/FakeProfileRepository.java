package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfilePolicySnapshot;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.ProfileRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Pair with a {@link FakeProfileHouseholdShareRepository} so availability follows the shares. */
public class FakeProfileRepository extends FakeJpaRepository<Profile> implements ProfileRepository {

  private final FakeProfileHouseholdShareRepository shares;
  private final Map<UUID, UUID> linkedAccountsByProfile = new HashMap<>();

  public FakeProfileRepository() {
    this(new FakeProfileHouseholdShareRepository());
  }

  public FakeProfileRepository(FakeProfileHouseholdShareRepository shares) {
    this.shares = shares;
  }

  public FakeProfileHouseholdShareRepository shares() {
    return shares;
  }

  @Override
  public boolean lockByShareId(UUID shareId) {
    return shares.findById(shareId).map(share -> existsById(share.getProfileId())).orElse(false);
  }

  @Override
  public boolean lockSharedByShareId(UUID shareId) {
    return lockByShareId(shareId);
  }

  @Override
  public Optional<ProfilePolicySnapshot> lockPolicyById(UUID profileId) {
    return findById(profileId)
        .map(
            profile ->
                new ProfilePolicySnapshot(
                    profile.getKind(),
                    profile.getMaximumAllowedRatingAge(),
                    linkedAccountsByProfile.get(profileId)));
  }

  @Override
  public boolean lockIfUnrestricted(UUID profileId) {
    return findById(profileId).filter(profile -> !profile.isRestricted()).isPresent();
  }

  @Override
  public boolean lockById(UUID profileId) {
    return findById(profileId).isPresent();
  }

  @Override
  public void lockProfileAvailabilityAcrossHouseholds(UUID profileId) {
    // Row-lock ordering is a PostgreSQL concern and has no in-memory fake equivalent.
  }

  /** Marks the Profile as some Account's Personal Profile for policy snapshots. */
  public void linkTo(UUID profileId, UUID accountId) {
    linkedAccountsByProfile.put(profileId, accountId);
  }

  @Override
  public boolean tryApplyPolicy(UUID profileId, ProfilePolicyTarget target) {
    var profile = findById(profileId);
    profile.ifPresent(
        current -> {
          current.setKind(target.kind());
          current.setMaximumAllowedRatingAge(target.maximumAllowedRatingAge());
        });
    return profile.isPresent();
  }

  @Override
  public boolean tryRename(UUID profileId, String name) {
    var profile = findById(profileId);
    profile.ifPresent(current -> current.setName(name));
    return profile.isPresent();
  }

  @Override
  public boolean trySetPicture(UUID profileId, String picture) {
    var profile = findById(profileId);
    profile.ifPresent(current -> current.setPicture(picture));
    return profile.isPresent();
  }

  @Override
  public boolean trySetPinHash(UUID profileId, String pinHash) {
    var profile = findById(profileId);
    profile.ifPresent(current -> current.setPinHash(pinHash));
    return profile.isPresent();
  }

  @Override
  public Optional<Profile> findRefreshedById(UUID profileId) {
    return findById(profileId);
  }

  @Override
  public List<Profile> findByHouseholdId(UUID householdId) {
    return database.values().stream()
        .filter(profile -> householdId.equals(profile.getHouseholdId()))
        .toList();
  }

  @Override
  public List<Profile> findAvailableInHousehold(UUID householdId) {
    var available =
        shares.findByHouseholdIdAndStatus(householdId, ProfileShareStatus.ACTIVE).stream()
            .map(share -> share.getProfileId())
            .toList();
    return database.values().stream()
        .filter(profile -> available.contains(profile.getId()))
        .sorted(Comparator.comparing(Profile::getName).thenComparing(Profile::getId))
        .toList();
  }

  @Override
  public boolean existsAvailableInHouseholdWithNameIgnoreCase(UUID householdId, String name) {
    return findAvailableInHousehold(householdId).stream()
        .map(Profile::getName)
        .anyMatch(name::equalsIgnoreCase);
  }
}
