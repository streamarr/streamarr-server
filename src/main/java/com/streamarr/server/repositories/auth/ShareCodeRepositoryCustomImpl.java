package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ShareCode.SHARE_CODE;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.auth.ShareCodeFilter;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.jooq.generated.enums.ShareCodeStatus;
import com.streamarr.server.jooq.generated.tables.records.ShareCodeRecord;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

@RequiredArgsConstructor
public class ShareCodeRepositoryCustomImpl implements ShareCodeRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public Optional<UUID> tryInsert(ShareCode unsaved) {
    return dsl.insertInto(SHARE_CODE)
        .set(SHARE_CODE.HOUSEHOLD_ID, unsaved.getHouseholdId())
        .set(SHARE_CODE.ISSUER_ACCOUNT_ID, unsaved.getIssuerAccountId())
        .set(SHARE_CODE.CODE_DIGEST, unsaved.getCodeDigest())
        .set(SHARE_CODE.EXPIRES_AT, unsaved.getExpiresAt().atOffset(ZoneOffset.UTC))
        .onConflict(SHARE_CODE.CODE_DIGEST)
        .doNothing()
        .returning(SHARE_CODE.ID)
        .fetchOptional()
        .map(ShareCodeRecord::getId);
  }

  @Override
  public boolean trySpend(UUID shareCodeId, Instant now) {
    return endLive(shareCodeId, ShareCodeStatus.SPENT, now);
  }

  @Override
  public boolean tryWithdraw(UUID shareCodeId, Instant now) {
    return endLive(shareCodeId, ShareCodeStatus.WITHDRAWN, now);
  }

  @Override
  public List<UUID> findHouseholdIdsWithLiveCodesIssuedBy(UUID issuerAccountId, Instant now) {
    return dsl.selectDistinct(SHARE_CODE.HOUSEHOLD_ID)
        .from(SHARE_CODE)
        .where(SHARE_CODE.ISSUER_ACCOUNT_ID.eq(issuerAccountId))
        .and(SHARE_CODE.STATUS.eq(ShareCodeStatus.LIVE))
        .and(SHARE_CODE.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
        .fetch(SHARE_CODE.HOUSEHOLD_ID);
  }

  @Override
  public int invalidateLiveIssuedBy(UUID issuerAccountId, Instant now) {
    return endLiveWhere(
        SHARE_CODE.ISSUER_ACCOUNT_ID.eq(issuerAccountId), ShareCodeStatus.INVALIDATED, now);
  }

  @Override
  public int invalidateLiveIssuedFor(UUID issuerAccountId, UUID householdId, Instant now) {
    return endLiveWhere(
        SHARE_CODE
            .ISSUER_ACCOUNT_ID
            .eq(issuerAccountId)
            .and(SHARE_CODE.HOUSEHOLD_ID.eq(householdId)),
        ShareCodeStatus.INVALIDATED,
        now);
  }

  @Override
  public List<Row<ShareCode>> rowsInHousehold(ShareCodeFilter filter, Seek seek) {
    var keyset =
        Keyset.<OffsetDateTime>builder()
            .sortValue(SHARE_CODE.CREATED_ON)
            .itemId(SHARE_CODE.ID)
            .build();
    var scope = SHARE_CODE.HOUSEHOLD_ID.eq(filter.householdId()).and(keptBy(filter));
    var query = keyset.rowsBeyond(keyset.selectFrom(dsl, SHARE_CODE), scope, seek);
    return JooqQueryHelper.nativeRows(entityManager, query, ShareCode.class, keyset);
  }

  /** The codes the filter keeps, each read as of the filter's instant. */
  private static Condition keptBy(ShareCodeFilter filter) {
    if (filter.statuses().isEmpty()) {
      return DSL.noCondition();
    }

    var at = inline(filter.at().atOffset(ZoneOffset.UTC));
    return DSL.or(
        filter.statuses().stream()
            .map(status -> readsAs(ShareCodeStatus.valueOf(status.name()), at))
            .toList());
  }

  private static Condition readsAs(ShareCodeStatus status, Field<OffsetDateTime> at) {
    var stored = SHARE_CODE.STATUS.eq(inline(status));
    var live = SHARE_CODE.STATUS.eq(inline(ShareCodeStatus.LIVE));
    return switch (status) {
      case LIVE -> stored.and(SHARE_CODE.EXPIRES_AT.gt(at));
      case EXPIRED -> stored.or(live.and(SHARE_CODE.EXPIRES_AT.le(at)));
      case SPENT, WITHDRAWN, INVALIDATED -> stored;
    };
  }

  @Override
  public Optional<ShareCode> findByIdAndReloadFromDatabase(UUID shareCodeId) {
    var found = Optional.ofNullable(entityManager.find(ShareCode.class, shareCodeId));
    found.ifPresent(entityManager::refresh);
    return found;
  }

  private boolean endLive(UUID shareCodeId, ShareCodeStatus ended, Instant now) {
    return endLiveWhere(SHARE_CODE.ID.eq(shareCodeId), ended, now) > 0;
  }

  /** Ends the codes the condition names that are live at the instant; answers how many. */
  private int endLiveWhere(Condition codes, ShareCodeStatus ended, Instant now) {
    var at = now.atOffset(ZoneOffset.UTC);
    return dsl.update(SHARE_CODE)
        .set(SHARE_CODE.STATUS, ended)
        .set(SHARE_CODE.ENDED_AT, at)
        .where(codes)
        .and(SHARE_CODE.STATUS.eq(ShareCodeStatus.LIVE))
        .and(SHARE_CODE.EXPIRES_AT.gt(at))
        .execute();
  }
}
