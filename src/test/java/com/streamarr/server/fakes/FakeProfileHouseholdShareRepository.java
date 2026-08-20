package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeProfileHouseholdShareRepository extends FakeJpaRepository<ProfileHouseholdShare>
    implements ProfileHouseholdShareRepository {

  /** Shares a Profile into a Household as ACTIVE (structural when asked). */
  public ProfileHouseholdShare share(UUID profileId, UUID householdId, boolean structural) {
    return save(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .structural(structural)
            .build());
  }

  @Override
  public Optional<ProfileHouseholdShare> findByProfileIdAndHouseholdIdAndStatus(
      UUID profileId, UUID householdId, ProfileShareStatus status) {
    return database.values().stream()
        .filter(share -> share.getProfileId().equals(profileId))
        .filter(share -> share.getHouseholdId().equals(householdId))
        .filter(share -> share.getStatus() == status)
        .findFirst();
  }

  @Override
  public List<ProfileHouseholdShare> findByHouseholdIdAndStatus(
      UUID householdId, ProfileShareStatus status) {
    return database.values().stream()
        .filter(share -> share.getHouseholdId().equals(householdId))
        .filter(share -> share.getStatus() == status)
        .toList();
  }

  @Override
  public List<ProfileHouseholdShare> findByProfileIdAndStatus(
      UUID profileId, ProfileShareStatus status) {
    return database.values().stream()
        .filter(share -> share.getProfileId().equals(profileId))
        .filter(share -> share.getStatus() == status)
        .toList();
  }

  @Override
  public boolean tryActivate(UUID shareId, Instant now) {
    var share =
        findById(shareId)
            .filter(offer -> offer.getStatus() == ProfileShareStatus.PENDING)
            .filter(offer -> offer.getExpiresAt() == null || offer.getExpiresAt().isAfter(now));
    share.ifPresent(
        offer -> {
          offer.setStatus(ProfileShareStatus.ACTIVE);
          offer.setDecidedAt(now);
        });
    return share.isPresent();
  }

  @Override
  public boolean tryDecline(UUID shareId, ProfileShareStatus target, Instant now) {
    var share = findById(shareId).filter(offer -> offer.getStatus() == ProfileShareStatus.PENDING);
    share.ifPresent(
        offer -> {
          offer.setStatus(target);
          offer.setDecidedAt(now);
        });
    return share.isPresent();
  }

  @Override
  public boolean tryEnd(UUID shareId, Instant now) {
    var share = findById(shareId).filter(offer -> offer.getStatus() == ProfileShareStatus.ACTIVE);
    share.ifPresent(
        offer -> {
          offer.setStatus(ProfileShareStatus.ENDED);
          offer.setEndedAt(now);
        });
    return share.isPresent();
  }

  @Override
  public boolean hasLiveOrPendingShares(UUID profileId) {
    return database.values().stream()
        .filter(share -> share.getProfileId().equals(profileId))
        .anyMatch(
            share ->
                share.getStatus() == ProfileShareStatus.ACTIVE
                    || share.getStatus() == ProfileShareStatus.PENDING);
  }

  @Override
  public List<ProfileHouseholdShare> findByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(share -> share.getProfileId().equals(profileId))
        .toList();
  }

  @Override
  public boolean isActivelyShared(UUID profileId, UUID householdId) {
    return findByProfileIdAndHouseholdIdAndStatus(profileId, householdId, ProfileShareStatus.ACTIVE)
        .isPresent();
  }

  @Override
  public boolean lockActiveShare(UUID profileId, UUID householdId) {
    return isActivelyShared(profileId, householdId);
  }
}
