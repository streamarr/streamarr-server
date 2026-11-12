package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.HouseholdShareFilter;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ShareStatusFilter;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.jooq.generated.enums.ProfileShareStatus;
import com.streamarr.server.jooq.generated.tables.records.ProfileHouseholdShareRecord;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.UpdateResultStep;
import org.jooq.impl.DSL;

@RequiredArgsConstructor
public class ProfileHouseholdShareRepositoryCustomImpl
    implements ProfileHouseholdShareRepositoryCustom {

  // The columns a write returns to name each share it changed and the status it left it in.
  private static final List<Field<?>> CHANGED_SHARE =
      List.of(
          PROFILE_HOUSEHOLD_SHARE.ID,
          PROFILE_HOUSEHOLD_SHARE.PROFILE_ID,
          PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID,
          PROFILE_HOUSEHOLD_SHARE.STATUS);

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public List<ChangedShare> supersedePending(UUID profileId, UUID householdId, Instant now) {
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    return changed(
        dsl.update(PROFILE_HOUSEHOLD_SHARE)
            .set(
                PROFILE_HOUSEHOLD_SHARE.STATUS,
                when(PROFILE_HOUSEHOLD_SHARE.EXPIRES_AT.le(nowUtc), ProfileShareStatus.EXPIRED)
                    .otherwise(ProfileShareStatus.CANCELED))
            .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, nowUtc)
            .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId))
            .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId))
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.PENDING))
            .returning(CHANGED_SHARE));
  }

  @Override
  public Optional<ChangedShare> tryInvalidatePending(UUID shareId, String reason, Instant now) {
    return onlyChanged(
        invalidatePending(
            PROFILE_HOUSEHOLD_SHARE
                .ID
                .eq(shareId)
                .and(unexpiredAt(val(now.atOffset(ZoneOffset.UTC)))),
            reason,
            now));
  }

  @Override
  public List<ChangedShare> invalidatePendingOfferedBy(
      UUID offererAccountId, String reason, Instant now) {
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    return invalidatePending(
        PROFILE_HOUSEHOLD_SHARE
            .OFFERED_BY_ACCOUNT_ID
            .eq(offererAccountId)
            .and(unexpiredAt(val(nowUtc))),
        reason,
        now);
  }

  private List<ChangedShare> invalidatePending(Condition scope, String reason, Instant now) {
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    return changed(
        dsl.update(PROFILE_HOUSEHOLD_SHARE)
            .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.INVALIDATED)
            .set(PROFILE_HOUSEHOLD_SHARE.INVALIDATION_REASON, reason)
            .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, nowUtc)
            .where(scope)
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.PENDING))
            .returning(CHANGED_SHARE));
  }

  private static List<ChangedShare> changed(UpdateResultStep<ProfileHouseholdShareRecord> update) {
    return update.fetch().map(ProfileHouseholdShareRepositoryCustomImpl::changedShare);
  }

  private static ChangedShare changedShare(ProfileHouseholdShareRecord shareRecord) {
    return new ChangedShare(
        shareRecord.getId(),
        shareRecord.getProfileId(),
        shareRecord.getHouseholdId(),
        domainStatusOf(shareRecord.getStatus()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.domain.auth.ProfileShareStatus domainStatusOf(
      ProfileShareStatus status) {
    return com.streamarr.server.domain.auth.ProfileShareStatus.valueOf(status.name());
  }

  // Each single-share write names its share by id, so it changes at most one row.
  private static Optional<ChangedShare> onlyChanged(List<ChangedShare> changed) {
    return changed.stream().findFirst();
  }

  @Override
  public Optional<ProfileHouseholdShare> findByIdAndReloadFromDatabase(UUID shareId) {
    // The held copy is dropped and the row read again: refreshing a copy whose row another
    // transaction deleted would fail where the share is simply gone.
    var held = Optional.ofNullable(entityManager.find(ProfileHouseholdShare.class, shareId));
    held.ifPresent(entityManager::detach);
    return held.map(_ -> entityManager.find(ProfileHouseholdShare.class, shareId));
  }

  @Override
  public List<ProfileHouseholdShare> findByProfileIdAndReloadFromDatabase(UUID profileId) {
    return reloadedFromDatabase(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId));
  }

  @Override
  public List<ProfileHouseholdShare> findByHouseholdIdAndReloadFromDatabase(UUID householdId) {
    return reloadedFromDatabase(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId));
  }

  // A query answers with the copy the transaction already holds, as it was first read. The copies
  // are dropped, so the second read builds each share from its row as stored now.
  private List<ProfileHouseholdShare> reloadedFromDatabase(Condition scope) {
    var query = dsl.selectFrom(PROFILE_HOUSEHOLD_SHARE).where(scope);
    JooqQueryHelper.nativeQuery(entityManager, query, ProfileHouseholdShare.class)
        .forEach(entityManager::detach);
    return JooqQueryHelper.nativeQuery(entityManager, query, ProfileHouseholdShare.class);
  }

  @Override
  public List<Row<ProfileHouseholdShare>> rowsIntoHousehold(
      HouseholdShareFilter filter, Seek seek) {
    return rowsBeyond(
        PROFILE_HOUSEHOLD_SHARE
            .HOUSEHOLD_ID
            .eq(filter.householdId())
            .and(keptBy(filter.statuses()))
            .and(filter.visitorsOnly() ? ofVisitor() : DSL.noCondition()),
        seek);
  }

  /** A share of a Profile that belongs to another Household than the one the share is into. */
  private static Condition ofVisitor() {
    return DSL.exists(
        DSL.selectOne()
            .from(PROFILE)
            .where(PROFILE.ID.eq(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID))
            .and(PROFILE.HOUSEHOLD_ID.ne(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID)));
  }

  /** The shares the filter keeps, each read as of the filter's instant. */
  private static Condition keptBy(ShareStatusFilter filter) {
    if (filter.statuses().isEmpty()) {
      return DSL.noCondition();
    }

    var at = inline(filter.at().atOffset(ZoneOffset.UTC));
    return DSL.or(
        filter.statuses().stream()
            .map(status -> readsAs(ProfileShareStatus.valueOf(status.name()), at))
            .toList());
  }

  private static Condition readsAs(ProfileShareStatus status, Field<OffsetDateTime> at) {
    var stored = PROFILE_HOUSEHOLD_SHARE.STATUS.eq(inline(status));
    return switch (status) {
      case PENDING -> stored.and(unexpiredAt(at));
      case EXPIRED ->
          stored.or(
              PROFILE_HOUSEHOLD_SHARE
                  .STATUS
                  .eq(inline(ProfileShareStatus.PENDING))
                  .and(PROFILE_HOUSEHOLD_SHARE.EXPIRES_AT.le(at)));
      case ACTIVE, REJECTED, CANCELED, INVALIDATED, ENDED -> stored;
    };
  }

  /** Expiry is a predicate over expires_at; a NULL expiry never expires. */
  private static Condition unexpiredAt(Field<OffsetDateTime> now) {
    return PROFILE_HOUSEHOLD_SHARE
        .EXPIRES_AT
        .isNull()
        .or(PROFILE_HOUSEHOLD_SHARE.EXPIRES_AT.gt(now));
  }

  @Override
  public List<Row<ProfileHouseholdShare>> rowsOfProfile(
      UUID profileId, ShareStatusFilter filter, Seek seek) {
    return rowsBeyond(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId).and(keptBy(filter)), seek);
  }

  private List<Row<ProfileHouseholdShare>> rowsBeyond(Condition scope, Seek seek) {
    var keyset =
        Keyset.<OffsetDateTime>builder()
            .sortValue(PROFILE_HOUSEHOLD_SHARE.CREATED_ON)
            .itemId(PROFILE_HOUSEHOLD_SHARE.ID)
            .build();
    var query = keyset.rowsBeyond(keyset.selectFrom(dsl, PROFILE_HOUSEHOLD_SHARE), scope, seek);
    return JooqQueryHelper.nativeRows(entityManager, query, ProfileHouseholdShare.class, keyset);
  }

  @Override
  public Optional<ChangedShare> tryActivatePending(UUID shareId, Instant now) {
    return onlyChanged(
        changed(
            dsl.update(PROFILE_HOUSEHOLD_SHARE)
                .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.ACTIVE)
                .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
                .where(PROFILE_HOUSEHOLD_SHARE.ID.eq(shareId))
                .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.PENDING))
                .and(unexpiredAt(val(now.atOffset(ZoneOffset.UTC))))
                .returning(CHANGED_SHARE)));
  }

  @Override
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  public Optional<ChangedShare> tryDeclinePending(
      UUID shareId, com.streamarr.server.domain.auth.ProfileShareStatus target, Instant now)
      throws IllegalArgumentException {
    ProfileHouseholdShareRepositoryCustom.requireDeclineTarget(target);
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    return onlyChanged(
        changed(
            dsl.update(PROFILE_HOUSEHOLD_SHARE)
                .set(
                    PROFILE_HOUSEHOLD_SHARE.STATUS,
                    when(PROFILE_HOUSEHOLD_SHARE.EXPIRES_AT.le(nowUtc), ProfileShareStatus.EXPIRED)
                        .otherwise(ProfileShareStatus.valueOf(target.name())))
                .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
                .where(PROFILE_HOUSEHOLD_SHARE.ID.eq(shareId))
                .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.PENDING))
                .returning(CHANGED_SHARE)));
  }

  @Override
  public Optional<ChangedShare> tryEndActive(UUID shareId, Instant now) {
    return onlyChanged(
        changed(
            dsl.update(PROFILE_HOUSEHOLD_SHARE)
                .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.ENDED)
                .set(PROFILE_HOUSEHOLD_SHARE.ENDED_AT, now.atOffset(ZoneOffset.UTC))
                .where(PROFILE_HOUSEHOLD_SHARE.ID.eq(shareId))
                .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.ACTIVE))
                .returning(CHANGED_SHARE)));
  }

  @Override
  public List<ChangedShare> ensureActiveMembershipShare(
      UUID profileId, UUID householdId, Instant now) {
    var changed = new ArrayList<>(materializeExpiredPending(profileId, householdId, now));
    var timestamp = now.atOffset(ZoneOffset.UTC);
    dsl.insertInto(PROFILE_HOUSEHOLD_SHARE)
        .set(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID, profileId)
        .set(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID, householdId)
        .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.ACTIVE)
        .set(PROFILE_HOUSEHOLD_SHARE.STRUCTURAL, true)
        .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, timestamp)
        .onConflict(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID, PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID)
        .where(
            PROFILE_HOUSEHOLD_SHARE.STATUS.in(
                ProfileShareStatus.PENDING, ProfileShareStatus.ACTIVE))
        .doUpdate()
        .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.ACTIVE)
        .set(PROFILE_HOUSEHOLD_SHARE.STRUCTURAL, true)
        .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, timestamp)
        .returning(CHANGED_SHARE)
        .fetch()
        .forEach(shareRecord -> changed.add(changedShare(shareRecord)));
    return List.copyOf(changed);
  }

  @Override
  public List<ChangedShare> materializeExpiredPending(Instant now) {
    return materializeExpiredPending(DSL.noCondition(), now);
  }

  private List<ChangedShare> materializeExpiredPending(
      UUID profileId, UUID householdId, Instant now) {
    return materializeExpiredPending(
        PROFILE_HOUSEHOLD_SHARE
            .PROFILE_ID
            .eq(profileId)
            .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId)),
        now);
  }

  private List<ChangedShare> materializeExpiredPending(UUID profileId, Instant now) {
    return materializeExpiredPending(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId), now);
  }

  private List<ChangedShare> materializeExpiredPending(Condition scope, Instant now) {
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    return changed(
        dsl.update(PROFILE_HOUSEHOLD_SHARE)
            .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.EXPIRED)
            .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, nowUtc)
            .where(scope)
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.PENDING))
            .and(PROFILE_HOUSEHOLD_SHARE.EXPIRES_AT.le(nowUtc))
            .returning(CHANGED_SHARE));
  }

  @Override
  public List<ChangedShare> invalidatePendingByProfileId(
      UUID profileId, String reason, Instant now) {
    return concat(
        materializeExpiredPending(profileId, now),
        invalidatePending(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId), reason, now));
  }

  @Override
  public List<ChangedShare> convertMembershipShareToVisitorShare(UUID profileId, UUID householdId) {
    return changed(
        dsl.update(PROFILE_HOUSEHOLD_SHARE)
            .set(PROFILE_HOUSEHOLD_SHARE.STRUCTURAL, false)
            .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId))
            .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId))
            .and(PROFILE_HOUSEHOLD_SHARE.STRUCTURAL.isTrue())
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.ACTIVE))
            .returning(CHANGED_SHARE));
  }

  @Override
  public List<ChangedShare> invalidatePendingOffersByProfileIdAndOffererAccountId(
      UUID profileId, UUID offererAccountId, String reason, Instant now) {
    return concat(
        materializeExpiredPending(profileId, now),
        invalidatePending(
            PROFILE_HOUSEHOLD_SHARE
                .PROFILE_ID
                .eq(profileId)
                .and(PROFILE_HOUSEHOLD_SHARE.OFFERED_BY_ACCOUNT_ID.eq(offererAccountId)),
            reason,
            now));
  }

  private static List<ChangedShare> concat(List<ChangedShare> first, List<ChangedShare> second) {
    var changed = new ArrayList<>(first);
    changed.addAll(second);
    return List.copyOf(changed);
  }

  @Override
  public boolean hasActiveOrPendingShares(UUID profileId, Instant now) {
    var pendingAndLive =
        PROFILE_HOUSEHOLD_SHARE
            .STATUS
            .eq(ProfileShareStatus.PENDING)
            .and(unexpiredAt(val(now.atOffset(ZoneOffset.UTC))));
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PROFILE_HOUSEHOLD_SHARE)
            .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId))
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.ACTIVE).or(pendingAndLive)));
  }

  @Override
  public boolean isActivelyShared(UUID profileId, UUID householdId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PROFILE_HOUSEHOLD_SHARE)
            .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId))
            .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId))
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.ACTIVE)));
  }

  @Override
  public Set<UUID> findProfileIdsActivelySharedInto(UUID householdId, Collection<UUID> profileIds) {
    return dsl.select(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID)
        .from(PROFILE_HOUSEHOLD_SHARE)
        .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.in(profileIds))
        .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId))
        .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.ACTIVE))
        .fetchSet(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID);
  }

  @Override
  public boolean lockActiveShare(UUID profileId, UUID householdId) {
    return dsl.select(PROFILE_HOUSEHOLD_SHARE.ID)
        .from(PROFILE_HOUSEHOLD_SHARE)
        .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId))
        .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId))
        .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.ACTIVE))
        .forShare()
        .fetchOptional()
        .isPresent();
  }
}
