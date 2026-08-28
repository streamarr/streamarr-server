package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.jooq.generated.enums.ProfileShareStatus;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.data.domain.AuditorAware;

@SuppressWarnings("checkstyle:fullyQualifiedName")
@RequiredArgsConstructor
public class ProfileHouseholdShareRepositoryCustomImpl
    implements ProfileHouseholdShareRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public int supersedePending(UUID profileId, UUID householdId, Instant now) {
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    return dsl.update(PROFILE_HOUSEHOLD_SHARE)
        .set(
            PROFILE_HOUSEHOLD_SHARE.STATUS,
            when(PROFILE_HOUSEHOLD_SHARE.EXPIRES_AT.le(nowUtc), ProfileShareStatus.EXPIRED)
                .otherwise(ProfileShareStatus.CANCELED))
        .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, nowUtc)
        .set(PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_ON, nowUtc)
        .set(
            PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
        .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId))
        .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId))
        .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.PENDING))
        .execute();
  }

  @Override
  public boolean tryInvalidatePending(UUID shareId, String reason, Instant now) {
    return invalidatePending(
            PROFILE_HOUSEHOLD_SHARE
                .ID
                .eq(shareId)
                .and(unexpiredAt(val(now.atOffset(ZoneOffset.UTC)))),
            reason,
            now)
        > 0;
  }

  @Override
  public int invalidatePendingOfferedBy(UUID offererAccountId, String reason, Instant now) {
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    return invalidatePending(
        PROFILE_HOUSEHOLD_SHARE
            .OFFERED_BY_ACCOUNT_ID
            .eq(offererAccountId)
            .and(unexpiredAt(val(nowUtc))),
        reason,
        now);
  }

  private int invalidatePending(Condition scope, String reason, Instant now) {
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    return dsl.update(PROFILE_HOUSEHOLD_SHARE)
        .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.INVALIDATED)
        .set(PROFILE_HOUSEHOLD_SHARE.INVALIDATION_REASON, reason)
        .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, nowUtc)
        .set(PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_ON, nowUtc)
        .set(
            PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
        .where(scope)
        .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.PENDING))
        .execute();
  }

  @Override
  public Optional<ProfileHouseholdShare> findRefreshedById(UUID shareId) {
    var found = Optional.ofNullable(entityManager.find(ProfileHouseholdShare.class, shareId));
    found.ifPresent(entityManager::refresh);
    return found;
  }

  @Override
  public List<ProfileHouseholdShare> findPendingByHouseholdId(
      UUID householdId, Instant now, KeysetPaginationOptions options) {
    return findPage(
        PROFILE_HOUSEHOLD_SHARE
            .HOUSEHOLD_ID
            .eq(householdId)
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(inline(ProfileShareStatus.PENDING)))
            .and(unexpiredAt(inline(now.atOffset(ZoneOffset.UTC)))),
        options);
  }

  /** Expiry is a predicate over expires_at; a NULL expiry never expires. */
  private static Condition unexpiredAt(Field<OffsetDateTime> now) {
    return PROFILE_HOUSEHOLD_SHARE
        .EXPIRES_AT
        .isNull()
        .or(PROFILE_HOUSEHOLD_SHARE.EXPIRES_AT.gt(now));
  }

  @Override
  public List<ProfileHouseholdShare> findByProfileId(
      UUID profileId, KeysetPaginationOptions options) {
    return findPage(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId), options);
  }

  private List<ProfileHouseholdShare> findPage(Condition scope, KeysetPaginationOptions options) {
    var pagination = options.getPaginationOptions();
    var reverse = pagination.getPaginationDirection() == PaginationDirection.REVERSE;
    var cursorCondition =
        options
            .getCursorId()
            .<Condition>map(
                cursorId ->
                    reverse
                        ? PROFILE_HOUSEHOLD_SHARE.ID.le(cursorId)
                        : PROFILE_HOUSEHOLD_SHARE.ID.ge(cursorId))
            .orElseGet(DSL::noCondition);
    var extraRows = options.getCursorId().isPresent() ? 2 : 1;
    var query =
        dsl.select()
            .from(PROFILE_HOUSEHOLD_SHARE)
            .where(scope)
            .and(cursorCondition)
            .orderBy(reverse ? PROFILE_HOUSEHOLD_SHARE.ID.desc() : PROFILE_HOUSEHOLD_SHARE.ID.asc())
            .limit(pagination.getLimit() + extraRows);
    var found = JooqQueryHelper.nativeQuery(entityManager, query, ProfileHouseholdShare.class);
    if (reverse) {
      Collections.reverse(found);
    }

    return found;
  }

  @Override
  public boolean tryActivatePending(UUID shareId, Instant now) {
    return dsl.update(PROFILE_HOUSEHOLD_SHARE)
            .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.ACTIVE)
            .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
            .set(PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
            .set(
                PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_BY,
                auditorAware.getCurrentAuditor().orElse(null))
            .where(PROFILE_HOUSEHOLD_SHARE.ID.eq(shareId))
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.PENDING))
            .and(unexpiredAt(val(now.atOffset(ZoneOffset.UTC))))
            .execute()
        > 0;
  }

  @Override
  public boolean tryDeclinePending(
      UUID shareId, com.streamarr.server.domain.auth.ProfileShareStatus target, Instant now)
      throws IllegalArgumentException {
    ProfileHouseholdShareRepositoryCustom.requireDeclineTarget(target);
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    return dsl.update(PROFILE_HOUSEHOLD_SHARE)
            .set(
                PROFILE_HOUSEHOLD_SHARE.STATUS,
                when(PROFILE_HOUSEHOLD_SHARE.EXPIRES_AT.le(nowUtc), ProfileShareStatus.EXPIRED)
                    .otherwise(ProfileShareStatus.valueOf(target.name())))
            .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
            .set(PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
            .set(
                PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_BY,
                auditorAware.getCurrentAuditor().orElse(null))
            .where(PROFILE_HOUSEHOLD_SHARE.ID.eq(shareId))
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.PENDING))
            .execute()
        > 0;
  }

  @Override
  public boolean tryEndActive(UUID shareId, Instant now) {
    return dsl.update(PROFILE_HOUSEHOLD_SHARE)
            .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.ENDED)
            .set(PROFILE_HOUSEHOLD_SHARE.ENDED_AT, now.atOffset(ZoneOffset.UTC))
            .set(PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
            .set(
                PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_BY,
                auditorAware.getCurrentAuditor().orElse(null))
            .where(PROFILE_HOUSEHOLD_SHARE.ID.eq(shareId))
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.ACTIVE))
            .execute()
        > 0;
  }

  @Override
  public void ensureActiveMembershipShare(UUID profileId, UUID householdId, Instant now) {
    materializeExpiredPending(profileId, householdId, now);
    var timestamp = now.atOffset(ZoneOffset.UTC);
    var auditor = auditorAware.getCurrentAuditor().orElse(null);
    dsl.insertInto(PROFILE_HOUSEHOLD_SHARE)
        .set(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID, profileId)
        .set(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID, householdId)
        .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.ACTIVE)
        .set(PROFILE_HOUSEHOLD_SHARE.STRUCTURAL, true)
        .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, timestamp)
        .set(PROFILE_HOUSEHOLD_SHARE.CREATED_ON, timestamp)
        .set(PROFILE_HOUSEHOLD_SHARE.CREATED_BY, auditor)
        .set(PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_ON, timestamp)
        .set(PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_BY, auditor)
        .onConflict(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID, PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID)
        .where(
            PROFILE_HOUSEHOLD_SHARE.STATUS.in(
                ProfileShareStatus.PENDING, ProfileShareStatus.ACTIVE))
        .doUpdate()
        .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.ACTIVE)
        .set(PROFILE_HOUSEHOLD_SHARE.STRUCTURAL, true)
        .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, timestamp)
        .set(PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_ON, timestamp)
        .set(PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_BY, auditor)
        .execute();
  }

  private void materializeExpiredPending(UUID profileId, UUID householdId, Instant now) {
    materializeExpiredPending(
        PROFILE_HOUSEHOLD_SHARE
            .PROFILE_ID
            .eq(profileId)
            .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId)),
        now);
  }

  private void materializeExpiredPending(UUID profileId, Instant now) {
    materializeExpiredPending(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId), now);
  }

  private void materializeExpiredPending(Condition scope, Instant now) {
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    dsl.update(PROFILE_HOUSEHOLD_SHARE)
        .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.EXPIRED)
        .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, nowUtc)
        .set(PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_ON, nowUtc)
        .set(
            PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
        .where(scope)
        .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.PENDING))
        .and(PROFILE_HOUSEHOLD_SHARE.EXPIRES_AT.le(nowUtc))
        .execute();
  }

  @Override
  public int invalidatePendingByProfileId(UUID profileId, String reason, Instant now) {
    materializeExpiredPending(profileId, now);
    return invalidatePending(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId), reason, now);
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
