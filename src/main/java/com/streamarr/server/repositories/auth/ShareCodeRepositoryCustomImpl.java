package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ShareCode.SHARE_CODE;

import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.jooq.generated.enums.ShareCodeStatus;
import com.streamarr.server.jooq.generated.tables.records.ShareCodeRecord;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

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
  public boolean tryInvalidate(UUID shareCodeId, Instant now) {
    return endLive(shareCodeId, ShareCodeStatus.INVALIDATED, now);
  }

  @Override
  public Optional<ShareCode> findByIdAndReloadFromDatabase(UUID shareCodeId) {
    var found = Optional.ofNullable(entityManager.find(ShareCode.class, shareCodeId));
    found.ifPresent(entityManager::refresh);
    return found;
  }

  private boolean endLive(UUID shareCodeId, ShareCodeStatus ended, Instant now) {
    var at = now.atOffset(ZoneOffset.UTC);
    return dsl.update(SHARE_CODE)
            .set(SHARE_CODE.STATUS, ended)
            .set(SHARE_CODE.ENDED_AT, at)
            .where(SHARE_CODE.ID.eq(shareCodeId))
            .and(SHARE_CODE.STATUS.eq(ShareCodeStatus.LIVE))
            .and(SHARE_CODE.EXPIRES_AT.gt(at))
            .execute()
        > 0;
  }
}
