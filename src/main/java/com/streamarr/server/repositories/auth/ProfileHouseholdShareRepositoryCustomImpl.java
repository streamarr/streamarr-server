package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;

import com.streamarr.server.jooq.generated.enums.ProfileShareStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class ProfileHouseholdShareRepositoryCustomImpl
    implements ProfileHouseholdShareRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public boolean tryActivate(UUID shareId, Instant now) {
    return dsl.update(PROFILE_HOUSEHOLD_SHARE)
            .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.ACTIVE)
            .set(PROFILE_HOUSEHOLD_SHARE.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
            .set(PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
            .set(
                PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_BY,
                auditorAware.getCurrentAuditor().orElse(null))
            .where(PROFILE_HOUSEHOLD_SHARE.ID.eq(shareId))
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.PENDING))
            .and(
                PROFILE_HOUSEHOLD_SHARE
                    .EXPIRES_AT
                    .isNull()
                    .or(PROFILE_HOUSEHOLD_SHARE.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC))))
            .execute()
        > 0;
  }

  @Override
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  public boolean tryDecline(
      UUID shareId, com.streamarr.server.domain.auth.ProfileShareStatus target, Instant now) {
    return dsl.update(PROFILE_HOUSEHOLD_SHARE)
            .set(PROFILE_HOUSEHOLD_SHARE.STATUS, ProfileShareStatus.valueOf(target.name()))
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
  public boolean tryEnd(UUID shareId, Instant now) {
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
  public boolean hasLiveOrPendingShares(UUID profileId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PROFILE_HOUSEHOLD_SHARE)
            .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId))
            .and(
                PROFILE_HOUSEHOLD_SHARE.STATUS.in(
                    ProfileShareStatus.ACTIVE, ProfileShareStatus.PENDING)));
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
}
