package com.streamarr.server.fakes;

import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepositoryCustom;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import java.time.Instant;
import java.util.Comparator;
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
  public List<ProfileHouseholdShare> findHouseholdPage(
      UUID householdId, ProfileShareStatus status, KeysetPaginationOptions options) {
    return page(
        database.values().stream()
            .filter(share -> share.getHouseholdId().equals(householdId))
            .filter(share -> share.getStatus() == status)
            .toList(),
        options);
  }

  @Override
  public List<ProfileHouseholdShare> findProfilePage(
      UUID profileId, KeysetPaginationOptions options) {
    return page(
        database.values().stream().filter(share -> share.getProfileId().equals(profileId)).toList(),
        options);
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
  public int retirePendingForPair(UUID profileId, UUID householdId, Instant now) {
    var pending =
        database.values().stream()
            .filter(share -> share.getProfileId().equals(profileId))
            .filter(share -> share.getHouseholdId().equals(householdId))
            .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
            .toList();
    pending.forEach(
        share -> {
          var expired = share.getExpiresAt() != null && !share.getExpiresAt().isAfter(now);
          share.setStatus(expired ? ProfileShareStatus.EXPIRED : ProfileShareStatus.CANCELED);
          share.setDecidedAt(now);
        });
    return pending.size();
  }

  @Override
  public boolean tryInvalidate(UUID shareId, String reason, Instant now) {
    var pending =
        findById(shareId).filter(share -> share.getStatus() == ProfileShareStatus.PENDING);
    pending.ifPresent(
        share -> {
          share.setStatus(ProfileShareStatus.INVALIDATED);
          share.setInvalidationReason(reason);
          share.setDecidedAt(now);
        });
    return pending.isPresent();
  }

  @Override
  public Optional<ProfileHouseholdShare> findFreshById(UUID shareId) {
    return findById(shareId);
  }

  @Override
  public boolean tryDecline(UUID shareId, ProfileShareStatus target, Instant now)
      throws IllegalArgumentException {
    ProfileHouseholdShareRepositoryCustom.requireDeclineTarget(target);
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
  public void upsertStructuralHomeShare(UUID profileId, UUID householdId, Instant now) {
    var live =
        database.values().stream()
            .filter(share -> share.getProfileId().equals(profileId))
            .filter(share -> share.getHouseholdId().equals(householdId))
            .filter(
                share ->
                    share.getStatus() == ProfileShareStatus.PENDING
                        || share.getStatus() == ProfileShareStatus.ACTIVE)
            .findFirst();
    if (live.isPresent()) {
      live.get().setStatus(ProfileShareStatus.ACTIVE);
      live.get().setStructural(true);
      live.get().setDecidedAt(now);
      return;
    }

    save(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .structural(true)
            .build());
  }

  @Override
  public int invalidatePendingSharesForProfile(UUID profileId, String reason, Instant now) {
    var pending =
        database.values().stream()
            .filter(share -> share.getProfileId().equals(profileId))
            .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
            .toList();
    pending.forEach(
        share -> {
          share.setStatus(ProfileShareStatus.INVALIDATED);
          share.setInvalidationReason(reason);
          share.setDecidedAt(now);
          AuditFieldSetter.setLastModifiedOn(share, now);
        });
    return pending.size();
  }

  @Override
  public boolean tryDemoteStructural(UUID profileId, UUID householdId, Instant now) {
    var structural =
        database.values().stream()
            .filter(share -> share.getProfileId().equals(profileId))
            .filter(share -> share.getHouseholdId().equals(householdId))
            .filter(ProfileHouseholdShare::isStructural)
            .filter(share -> share.getStatus() == ProfileShareStatus.ACTIVE)
            .findFirst();
    structural.ifPresent(share -> share.setStructural(false));
    return structural.isPresent();
  }

  @Override
  public int invalidatePendingSharesOfferedBy(
      UUID profileId, UUID offererAccountId, String reason, Instant now) {
    var pending =
        database.values().stream()
            .filter(share -> share.getProfileId().equals(profileId))
            .filter(share -> offererAccountId.equals(share.getOfferedByAccountId()))
            .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
            .toList();
    pending.forEach(
        share -> {
          share.setStatus(ProfileShareStatus.INVALIDATED);
          share.setInvalidationReason(reason);
          share.setDecidedAt(now);
          AuditFieldSetter.setLastModifiedOn(share, now);
        });
    return pending.size();
  }

  @Override
  public int invalidatePendingSharesOfferedByAccount(
      UUID offererAccountId, String reason, Instant now) {
    var pending =
        database.values().stream()
            .filter(share -> offererAccountId.equals(share.getOfferedByAccountId()))
            .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
            .toList();
    pending.forEach(
        share -> {
          share.setStatus(ProfileShareStatus.INVALIDATED);
          share.setInvalidationReason(reason);
          share.setDecidedAt(now);
        });
    return pending.size();
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

  private static List<ProfileHouseholdShare> page(
      List<ProfileHouseholdShare> matches, KeysetPaginationOptions options) {
    var ordered =
        matches.stream().sorted(Comparator.comparing(ProfileHouseholdShare::getId)).toList();
    var cursorIndex =
        options
            .getCursorId()
            .map(
                cursorId ->
                    ordered.stream().map(ProfileHouseholdShare::getId).toList().indexOf(cursorId))
            .orElse(-1);
    if (options.getCursorId().isPresent() && cursorIndex < 0) {
      return List.of();
    }

    var pagination = options.getPaginationOptions();
    var rowLimit = pagination.getLimit() + (options.getCursorId().isPresent() ? 2 : 1);
    if (pagination.getPaginationDirection() == PaginationDirection.REVERSE) {
      var to = options.getCursorId().isPresent() ? cursorIndex + 1 : ordered.size();
      return List.copyOf(ordered.subList(Math.max(0, to - rowLimit), to));
    }

    var from = options.getCursorId().isPresent() ? cursorIndex : 0;
    return List.copyOf(ordered.subList(from, Math.min(ordered.size(), from + rowLimit)));
  }
}
