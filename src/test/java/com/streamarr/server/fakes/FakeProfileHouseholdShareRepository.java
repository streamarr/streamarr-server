package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeProfileHouseholdShareRepository extends FakeJpaRepository<ProfileHouseholdShare>
    implements ProfileHouseholdShareRepository {

  @Override
  public List<ProfileHouseholdShare> findByHouseholdIdAndStatus(
      UUID householdId, ProfileShareStatus status) {
    return database.values().stream()
        .filter(share -> householdId.equals(share.getHouseholdId()))
        .filter(share -> status == share.getStatus())
        .toList();
  }

  @Override
  public List<ProfileHouseholdShare> findByProfileIdAndStatus(
      UUID profileId, ProfileShareStatus status) {
    return database.values().stream()
        .filter(share -> profileId.equals(share.getProfileId()))
        .filter(share -> status == share.getStatus())
        .toList();
  }

  @Override
  public List<ProfileHouseholdShare> findByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(share -> profileId.equals(share.getProfileId()))
        .toList();
  }

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

  @Override
  public Optional<ProfileHouseholdShare> findByProfileIdAndHouseholdId(
      UUID profileId, UUID householdId) {
    return database.values().stream()
        .filter(share -> profileId.equals(share.getProfileId()))
        .filter(share -> householdId.equals(share.getHouseholdId()))
        .findFirst();
  }

  @Override
  public long countByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(share -> profileId.equals(share.getProfileId()))
        .count();
  }
}
