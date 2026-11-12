package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.HouseholdProfilesFilter;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfilePolicySnapshot;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
import com.streamarr.server.domain.auth.ProfileReaderRelation;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortValue;
import com.streamarr.server.repositories.auth.ProfileRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.exception.NoDataFoundException;

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
  public Optional<String> findNameById(UUID profileId) {
    return findById(profileId).map(Profile::getName);
  }

  @Override
  public void lockProfileAvailabilityAcrossHouseholds(UUID profileId) {
    // Row-lock ordering is a PostgreSQL concern and has no in-memory fake equivalent.
  }

  @Override
  public void lockProfileTransitionAcrossHouseholds(
      UUID profileId, List<UUID> additionalHouseholdIds) {
    // Row-lock ordering is a PostgreSQL concern and has no in-memory fake equivalent.
  }

  @Override
  public void lockProfileDeletionAcrossHouseholds(UUID profileId) {
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
  public boolean tryRehome(UUID profileId, UUID expectedHouseholdId, UUID destinationHouseholdId) {
    var profile =
        findById(profileId).filter(current -> expectedHouseholdId.equals(current.getHouseholdId()));
    profile.ifPresent(found -> found.setHouseholdId(destinationHouseholdId));
    return profile.isPresent();
  }

  @Override
  public void rehomePersonalProfile(
      UUID profileId, UUID expectedHouseholdId, UUID destinationHouseholdId) {
    findById(profileId)
        .filter(current -> expectedHouseholdId.equals(current.getHouseholdId()))
        .orElseThrow(NoDataFoundException::new)
        .setHouseholdId(destinationHouseholdId);
  }

  @Override
  public boolean tryDeleteUnlinked(UUID profileId) {
    var profile = findById(profileId).filter(_ -> !linkedAccountsByProfile.containsKey(profileId));
    profile.ifPresent(_ -> deleteById(profileId));
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
  public Optional<Profile> findByIdAndReloadFromDatabase(UUID profileId) {
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
  public List<Row<Profile>> rowsAvailableInHousehold(UUID householdId, Seek seek) {
    return InMemoryKeyset.rowsBeyond(
        findAvailableInHousehold(householdId).stream()
            .map(
                profile ->
                    new Row<>(
                        profile,
                        new Position(
                            new SortValue.OfText(profile.getName().toLowerCase(Locale.ROOT)),
                            profile.getId())))
            .toList(),
        seek);
  }

  @Override
  public List<Profile> findAvailableKidsInHousehold(UUID householdId) {
    return findAvailableInHousehold(householdId).stream()
        .filter(profile -> profile.getKind() == ProfileKind.KID)
        .toList();
  }

  @Override
  public boolean existsAvailableInHouseholdWithNameIgnoreCase(UUID householdId, String name) {
    return findAvailableInHousehold(householdId).stream()
        .map(Profile::getName)
        .anyMatch(name::equalsIgnoreCase);
  }

  // Who manages or supervises a Profile is decided by SQL that mirrors the Cedar facts; only
  // PostgreSQL proves it (ProfileReaderAuthorityParityIT).
  @Override
  public List<ProfileReaderRelation> relationsTo(UUID accountId, Collection<UUID> profileIds) {
    throw new UnsupportedOperationException("Reader relations are read only from PostgreSQL");
  }

  @Override
  public List<Row<Profile>> rowsManagedBy(UUID accountId, Seek seek) {
    throw new UnsupportedOperationException("Managed Profiles are read only from PostgreSQL");
  }

  @Override
  public List<Row<Profile>> rowsInHousehold(HouseholdProfilesFilter filter, Seek seek) {
    throw new UnsupportedOperationException("Managed Profiles are read only from PostgreSQL");
  }

  @Override
  public Set<UUID> availableWhereTheyBelong(Collection<UUID> profileIds) {
    return profileIds.stream()
        .flatMap(id -> findById(id).stream())
        .filter(
            profile ->
                !availableAmong(profile.getHouseholdId(), List.of(profile.getId())).isEmpty())
        .map(Profile::getId)
        .collect(Collectors.toSet());
  }

  @Override
  public Set<UUID> availableAmong(UUID householdId, Collection<UUID> profileIds) {
    return findAvailableInHousehold(householdId).stream()
        .map(Profile::getId)
        .filter(profileIds::contains)
        .collect(Collectors.toSet());
  }
}
