package com.streamarr.server.fakes;

import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.HouseholdShareFilter;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.ShareStatusFilter;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepositoryCustom;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FakeProfileHouseholdShareRepository extends FakeJpaRepository<ProfileHouseholdShare>
    implements ProfileHouseholdShareRepository {

  private final Function<UUID, Optional<UUID>> householdOfProfile;

  /** A fake that knows no Profile's Household, so a list kept to visitors keeps no share. */
  public FakeProfileHouseholdShareRepository() {
    this(_ -> Optional.empty());
  }

  /** A fake that asks where each Profile belongs, as the real query reads the Profile row. */
  public FakeProfileHouseholdShareRepository(Function<UUID, Optional<UUID>> householdOfProfile) {
    this.householdOfProfile = householdOfProfile;
  }

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
  public List<ProfileHouseholdShare> findByOfferedByAccountIdAndStatus(
      UUID offeredByAccountId, ProfileShareStatus status) {
    return database.values().stream()
        .filter(share -> offeredByAccountId.equals(share.getOfferedByAccountId()))
        .filter(share -> share.getStatus() == status)
        .toList();
  }

  @Override
  public List<Row<ProfileHouseholdShare>> rowsIntoHousehold(
      HouseholdShareFilter filter, Seek seek) {
    return InMemoryKeyset.byCreationTime(
        database.values().stream().filter(share -> isKeptIntoHousehold(filter, share)).toList(),
        seek);
  }

  @Override
  public Optional<Row<ProfileHouseholdShare>> findRowIntoHousehold(
      HouseholdShareFilter filter, UUID shareId) {
    return findById(shareId)
        .filter(share -> isKeptIntoHousehold(filter, share))
        .map(InMemoryKeyset::rowByCreationTime);
  }

  private boolean isKeptIntoHousehold(HouseholdShareFilter filter, ProfileHouseholdShare share) {
    return share.getHouseholdId().equals(filter.householdId())
        && keeps(filter.statuses(), share)
        && (!filter.visitorsOnly() || ofVisitor(share));
  }

  // As the real query reads it: a share whose Profile belongs to another Household.
  private boolean ofVisitor(ProfileHouseholdShare share) {
    return householdOfProfile
        .apply(share.getProfileId())
        .filter(home -> !home.equals(share.getHouseholdId()))
        .isPresent();
  }

  @Override
  public List<Row<ProfileHouseholdShare>> rowsOfProfile(
      UUID profileId, ShareStatusFilter filter, Seek seek) {
    return InMemoryKeyset.byCreationTime(
        database.values().stream()
            .filter(share -> share.getProfileId().equals(profileId))
            .filter(share -> keeps(filter, share))
            .toList(),
        seek);
  }

  @Override
  public Optional<Row<ProfileHouseholdShare>> findRowOfProfile(
      UUID profileId, ShareStatusFilter filter, UUID shareId) {
    return findById(shareId)
        .filter(share -> share.getProfileId().equals(profileId))
        .filter(share -> keeps(filter, share))
        .map(InMemoryKeyset::rowByCreationTime);
  }

  // As the real query reads it: an offer past its expiry is EXPIRED, never PENDING.
  private static boolean keeps(ShareStatusFilter filter, ProfileHouseholdShare share) {
    return filter.statuses().isEmpty() || filter.statuses().contains(share.statusAt(filter.at()));
  }

  @Override
  public Optional<ChangedShare> tryActivatePending(UUID shareId, Instant now) {
    var share =
        findById(shareId)
            .filter(offer -> offer.getStatus() == ProfileShareStatus.PENDING)
            .filter(offer -> offer.getExpiresAt() == null || offer.getExpiresAt().isAfter(now));
    share.ifPresent(
        offer -> {
          offer.setStatus(ProfileShareStatus.ACTIVE);
          offer.setDecidedAt(now);
        });
    return share.map(ChangedShare::of);
  }

  @Override
  public List<ChangedShare> supersedePending(UUID profileId, UUID householdId, Instant now) {
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
    return changed(pending);
  }

  @Override
  public Optional<ChangedShare> tryInvalidatePending(UUID shareId, String reason, Instant now) {
    var pending =
        findById(shareId)
            .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
            .filter(share -> share.getExpiresAt() == null || share.getExpiresAt().isAfter(now));
    pending.ifPresent(
        share -> {
          share.setStatus(ProfileShareStatus.INVALIDATED);
          share.setInvalidationReason(reason);
          share.setDecidedAt(now);
        });
    return pending.map(ChangedShare::of);
  }

  @Override
  public List<ChangedShare> invalidatePendingOfferedBy(
      UUID offererAccountId, String reason, Instant now) {
    var pending =
        database.values().stream()
            .filter(share -> offererAccountId.equals(share.getOfferedByAccountId()))
            .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
            .filter(share -> share.getExpiresAt() == null || share.getExpiresAt().isAfter(now))
            .toList();
    pending.forEach(
        share -> {
          share.setStatus(ProfileShareStatus.INVALIDATED);
          share.setInvalidationReason(reason);
          share.setDecidedAt(now);
        });
    return changed(pending);
  }

  @Override
  public Optional<ProfileHouseholdShare> findByIdAndReloadFromDatabase(UUID shareId) {
    return findById(shareId);
  }

  @Override
  public List<ProfileHouseholdShare> findByProfileIdAndReloadFromDatabase(UUID profileId) {
    return findByProfileId(profileId);
  }

  @Override
  public List<ProfileHouseholdShare> findByHouseholdIdAndReloadFromDatabase(UUID householdId) {
    return findByHouseholdId(householdId);
  }

  @Override
  public Optional<ChangedShare> tryDeclinePending(
      UUID shareId, ProfileShareStatus target, Instant now) throws IllegalArgumentException {
    ProfileHouseholdShareRepositoryCustom.requireDeclineTarget(target);
    var share = findById(shareId).filter(offer -> offer.getStatus() == ProfileShareStatus.PENDING);
    share.ifPresent(
        offer -> {
          offer.setStatus(
              offer.statusAt(now) == ProfileShareStatus.EXPIRED
                  ? ProfileShareStatus.EXPIRED
                  : target);
          offer.setDecidedAt(now);
        });
    return share.map(ChangedShare::of);
  }

  @Override
  public Optional<ChangedShare> tryEndActive(UUID shareId, Instant now) {
    var share = findById(shareId).filter(offer -> offer.getStatus() == ProfileShareStatus.ACTIVE);
    share.ifPresent(
        offer -> {
          offer.setStatus(ProfileShareStatus.ENDED);
          offer.setEndedAt(now);
        });
    return share.map(ChangedShare::of);
  }

  @Override
  public List<ChangedShare> ensureActiveMembershipShare(
      UUID profileId, UUID householdId, Instant now) {
    var expired =
        database.values().stream()
            .filter(share -> share.getProfileId().equals(profileId))
            .filter(share -> share.getHouseholdId().equals(householdId))
            .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
            .filter(share -> share.statusAt(now) == ProfileShareStatus.EXPIRED)
            .toList();
    expired.forEach(
        share -> {
          share.setStatus(ProfileShareStatus.EXPIRED);
          share.setDecidedAt(now);
        });
    var live =
        database.values().stream()
            .filter(share -> share.getProfileId().equals(profileId))
            .filter(share -> share.getHouseholdId().equals(householdId))
            .filter(
                share ->
                    share.getStatus() == ProfileShareStatus.PENDING
                        || share.getStatus() == ProfileShareStatus.ACTIVE)
            .findFirst();
    var membership =
        live.orElseGet(
            () ->
                save(
                    ProfileHouseholdShare.builder()
                        .profileId(profileId)
                        .householdId(householdId)
                        .status(ProfileShareStatus.ACTIVE)
                        .structural(true)
                        .build()));
    membership.setStatus(ProfileShareStatus.ACTIVE);
    membership.setStructural(true);
    membership.setDecidedAt(now);
    return changed(Stream.concat(expired.stream(), Stream.of(membership)).toList());
  }

  @Override
  public List<ChangedShare> invalidatePendingByProfileId(
      UUID profileId, String reason, Instant now) {
    var expired = materializeExpiredPending(profileId, now);
    var pending =
        invalidateUnexpiredPending(share -> share.getProfileId().equals(profileId), reason, now);
    return changed(Stream.concat(expired.stream(), pending.stream()).toList());
  }

  @Override
  public List<ChangedShare> invalidatePendingByHouseholdId(
      UUID householdId, String reason, Instant now) {
    Predicate<ProfileHouseholdShare> intoHousehold =
        share -> share.getHouseholdId().equals(householdId);
    var expired = materializeExpiredPending(intoHousehold, now);
    var pending = invalidateUnexpiredPending(intoHousehold, reason, now);
    return changed(Stream.concat(expired.stream(), pending.stream()).toList());
  }

  @Override
  public List<ChangedShare> convertMembershipShareToVisitorShare(UUID profileId, UUID householdId) {
    var membership =
        database.values().stream()
            .filter(share -> share.getProfileId().equals(profileId))
            .filter(share -> share.getHouseholdId().equals(householdId))
            .filter(ProfileHouseholdShare::isStructural)
            .filter(share -> share.getStatus() == ProfileShareStatus.ACTIVE)
            .findFirst();
    membership.ifPresent(share -> share.setStructural(false));
    return changed(membership.stream().toList());
  }

  @Override
  public List<ChangedShare> invalidatePendingOffersByProfileIdAndOffererAccountId(
      UUID profileId, UUID offererAccountId, String reason, Instant now) {
    var expired = materializeExpiredPending(profileId, now);
    var pending =
        invalidateUnexpiredPending(
            share ->
                share.getProfileId().equals(profileId)
                    && offererAccountId.equals(share.getOfferedByAccountId()),
            reason,
            now);
    return changed(Stream.concat(expired.stream(), pending.stream()).toList());
  }

  private List<ProfileHouseholdShare> invalidateUnexpiredPending(
      Predicate<ProfileHouseholdShare> scope, String reason, Instant now) {
    var pending =
        database.values().stream()
            .filter(scope)
            .filter(share -> share.statusAt(now) == ProfileShareStatus.PENDING)
            .toList();
    pending.forEach(
        share -> {
          share.setStatus(ProfileShareStatus.INVALIDATED);
          share.setInvalidationReason(reason);
          share.setDecidedAt(now);
          AuditFieldSetter.setLastModifiedOn(share, now);
        });
    return pending;
  }

  @Override
  public List<ChangedShare> materializeExpiredPending(Instant now) {
    return changed(materializeExpiredPending(share -> true, now));
  }

  private List<ProfileHouseholdShare> materializeExpiredPending(UUID profileId, Instant now) {
    return materializeExpiredPending(share -> share.getProfileId().equals(profileId), now);
  }

  private List<ProfileHouseholdShare> materializeExpiredPending(
      Predicate<ProfileHouseholdShare> scope, Instant now) {
    var expired =
        database.values().stream()
            .filter(scope)
            .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
            .filter(share -> share.statusAt(now) == ProfileShareStatus.EXPIRED)
            .toList();
    expired.forEach(
        share -> {
          share.setStatus(ProfileShareStatus.EXPIRED);
          share.setDecidedAt(now);
          AuditFieldSetter.setLastModifiedOn(share, now);
        });
    return expired;
  }

  private static List<ChangedShare> changed(List<ProfileHouseholdShare> shares) {
    return shares.stream().map(ChangedShare::of).toList();
  }

  @Override
  public boolean hasActiveOrPendingShares(UUID profileId, Instant now) {
    return database.values().stream()
        .filter(share -> share.getProfileId().equals(profileId))
        .anyMatch(
            share ->
                share.getStatus() == ProfileShareStatus.ACTIVE
                    || share.statusAt(now) == ProfileShareStatus.PENDING);
  }

  @Override
  public List<ProfileHouseholdShare> findByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(share -> share.getProfileId().equals(profileId))
        .toList();
  }

  @Override
  public List<ProfileHouseholdShare> findByHouseholdId(UUID householdId) {
    return database.values().stream()
        .filter(share -> share.getHouseholdId().equals(householdId))
        .toList();
  }

  @Override
  public boolean isActivelyShared(UUID profileId, UUID householdId) {
    return findByProfileIdAndHouseholdIdAndStatus(profileId, householdId, ProfileShareStatus.ACTIVE)
        .isPresent();
  }

  @Override
  public Set<UUID> findProfileIdsActivelySharedInto(UUID householdId, Collection<UUID> profileIds) {
    return profileIds.stream()
        .filter(profileId -> isActivelyShared(profileId, householdId))
        .collect(Collectors.toSet());
  }

  @Override
  public boolean lockActiveShare(UUID profileId, UUID householdId) {
    return isActivelyShared(profileId, householdId);
  }
}
