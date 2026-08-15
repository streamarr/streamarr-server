package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareInsertResult;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeProfileHouseholdShareRepository extends FakeJpaRepository<ProfileHouseholdShare>
    implements ProfileHouseholdShareRepository {

  /**
   * Ensures that a pending profile-household share exists.
   *
   * @return the existing share with an insertion flag of {@code false}, or the
   *         newly created pending share with an insertion flag of {@code true}
   */
  @Override
  public synchronized ProfileHouseholdShareInsertResult insertPendingIfAbsent(
      UUID profileId, UUID householdId) {
    var existing = findByProfileIdAndHouseholdId(profileId, householdId);
    if (existing.isPresent()) {
      return new ProfileHouseholdShareInsertResult(existing.orElseThrow(), false);
    }
    var share =
        save(
            ProfileHouseholdShare.builder()
                .profileId(profileId)
                .householdId(householdId)
                .status(ProfileShareStatus.PENDING)
                .build());
    return new ProfileHouseholdShareInsertResult(share, true);
  }

  /**
   * Finds shares associated with a household and having the specified status.
   *
   * @param householdId the household identifier
   * @param status      the required share status
   * @return the matching profile-household shares
   */
  @Override
  public List<ProfileHouseholdShare> findByHouseholdIdAndStatus(
      UUID householdId, ProfileShareStatus status) {
    return database.values().stream()
        .filter(share -> householdId.equals(share.getHouseholdId()))
        .filter(share -> status == share.getStatus())
        .toList();
  }

  /**
   * Finds shares associated with any of the specified households and the given status.
   *
   * @param householdIds the household identifiers to match
   * @param status       the required share status
   * @return shares matching one of the household identifiers and the specified status
   */
  @Override
  public List<ProfileHouseholdShare> findByHouseholdIdInAndStatus(
      Collection<UUID> householdIds, ProfileShareStatus status) {
    return database.values().stream()
        .filter(share -> householdIds.contains(share.getHouseholdId()))
        .filter(share -> status == share.getStatus())
        .toList();
  }

  /**
   * Finds all profile-household shares for a profile with the specified status.
   *
   * @param profileId the profile identifier
   * @param status    the required share status
   * @return the matching profile-household shares
   */
  @Override
  public List<ProfileHouseholdShare> findByProfileIdAndStatus(
      UUID profileId, ProfileShareStatus status) {
    return database.values().stream()
        .filter(share -> profileId.equals(share.getProfileId()))
        .filter(share -> status == share.getStatus())
        .toList();
  }

  /**
   * Finds all household shares associated with a profile.
   *
   * @param profileId the profile identifier
   * @return the shares associated with the profile
   */
  @Override
  public List<ProfileHouseholdShare> findByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(share -> profileId.equals(share.getProfileId()))
        .toList();
  }

  /**
   * Determines whether a profile-household share has the specified status.
   *
   * @param profileId   the profile identifier
   * @param householdId the household identifier
   * @param status      the share status
   * @return {@code true} if a matching share exists, {@code false} otherwise
   */
  @Override
  public boolean existsByProfileIdAndHouseholdIdAndStatus(
      UUID profileId, UUID householdId, ProfileShareStatus status) {
    return database.values().stream()
        .anyMatch(
            share ->
                profileId.equals(share.getProfileId())
                    && householdId.equals(share.getHouseholdId())
                    && status == share.getStatus());
  }

  /**
   * Finds the first share associated with the specified profile and household.
   *
   * @param profileId   the profile identifier
   * @param householdId the household identifier
   * @return the matching share, if one exists
   */
  @Override
  public Optional<ProfileHouseholdShare> findByProfileIdAndHouseholdId(
      UUID profileId, UUID householdId) {
    return database.values().stream()
        .filter(share -> profileId.equals(share.getProfileId()))
        .filter(share -> householdId.equals(share.getHouseholdId()))
        .findFirst();
  }

  /**
   * Counts the household shares associated with a profile.
   *
   * @param profileId the profile identifier
   * @return the number of household shares associated with the profile
   */
  @Override
  public long countByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(share -> profileId.equals(share.getProfileId()))
        .count();
  }
}
