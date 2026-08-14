package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION;
import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.jooq.generated.enums.ProfileShareStatus;
import com.streamarr.server.repositories.JooqQueryHelper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class AuthSessionRepositoryCustomImpl implements AuthSessionRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  private final EntityManager entityManager;

  @Override
  public boolean hasLivePlaybackAuthority(PlaybackAuthority authority) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(AUTH_SESSION)
            .join(USER_ACCOUNT)
            .on(USER_ACCOUNT.ID.eq(AUTH_SESSION.ACCOUNT_ID))
            .join(PROFILE_HOUSEHOLD_SHARE)
            .on(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(AUTH_SESSION.ACTIVE_PROFILE_ID))
            .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(USER_ACCOUNT.HOME_HOUSEHOLD_ID))
            .where(AUTH_SESSION.ID.eq(authority.authSessionId()))
            .and(AUTH_SESSION.ACCOUNT_ID.eq(authority.accountId()))
            .and(USER_ACCOUNT.HOME_HOUSEHOLD_ID.eq(authority.householdId()))
            .and(AUTH_SESSION.ACTIVE_PROFILE_ID.eq(authority.profileId()))
            .and(AUTH_SESSION.REVOKED_AT.isNull())
            .and(USER_ACCOUNT.ENABLED.isTrue())
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.ACTIVE)));
  }

  @Override
  @Transactional
  public boolean revoke(UUID sessionId, SessionRevocationReason reason, Instant now) {
    var nowOffset = now.atOffset(ZoneOffset.UTC);

    return dsl.update(AUTH_SESSION)
            .set(AUTH_SESSION.REVOKED_AT, nowOffset)
            .set(
                AUTH_SESSION.REVOKED_REASON,
                com.streamarr.server.jooq.generated.enums.SessionRevocationReason.valueOf(
                    reason.name()))
            .set(AUTH_SESSION.LAST_MODIFIED_ON, nowOffset)
            .set(AUTH_SESSION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(AUTH_SESSION.ID.eq(sessionId))
            .and(AUTH_SESSION.REVOKED_AT.isNull())
            .execute()
        > 0;
  }

  @Override
  public boolean updateSelectionIfLive(AuthSession session, Instant now) {
    var nowOffset = now.atOffset(ZoneOffset.UTC);

    return dsl.update(AUTH_SESSION)
            .set(AUTH_SESSION.ACTIVE_PROFILE_ID, session.getActiveProfileId())
            .set(AUTH_SESSION.LAST_MODIFIED_ON, nowOffset)
            .set(AUTH_SESSION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(AUTH_SESSION.ID.eq(session.getId()))
            .and(AUTH_SESSION.REVOKED_AT.isNull())
            .execute()
        > 0;
  }

  @Override
  public int clearProfileSelection(UUID profileId, UUID householdId, Instant now) {
    var nowOffset = now.atOffset(ZoneOffset.UTC);

    return dsl.update(AUTH_SESSION)
        .setNull(AUTH_SESSION.ACTIVE_PROFILE_ID)
        .set(AUTH_SESSION.LAST_MODIFIED_ON, nowOffset)
        .set(AUTH_SESSION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
        .where(AUTH_SESSION.ACTIVE_PROFILE_ID.eq(profileId))
        .and(AUTH_SESSION.REVOKED_AT.isNull())
        .and(
            AUTH_SESSION.ACCOUNT_ID.in(
                dsl.select(USER_ACCOUNT.ID)
                    .from(USER_ACCOUNT)
                    .where(USER_ACCOUNT.HOME_HOUSEHOLD_ID.eq(householdId))))
        .execute();
  }

  @Override
  public int clearAccountSelections(UUID accountId, Instant now) {
    var nowOffset = now.atOffset(ZoneOffset.UTC);

    return dsl.update(AUTH_SESSION)
        .setNull(AUTH_SESSION.ACTIVE_PROFILE_ID)
        .set(AUTH_SESSION.LAST_MODIFIED_ON, nowOffset)
        .set(AUTH_SESSION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
        .where(AUTH_SESSION.ACCOUNT_ID.eq(accountId))
        .and(AUTH_SESSION.ACTIVE_PROFILE_ID.isNotNull())
        .execute();
  }

  @Override
  public boolean hasRow(UUID sessionId) {
    return dsl.fetchExists(dsl.selectOne().from(AUTH_SESSION).where(AUTH_SESSION.ID.eq(sessionId)));
  }

  @Override
  public Optional<AuthSession> lockById(UUID sessionId) {
    var query = dsl.selectFrom(AUTH_SESSION).where(AUTH_SESSION.ID.eq(sessionId)).forUpdate();

    return JooqQueryHelper.nativeQuery(entityManager, query, AuthSession.class).stream()
        .findFirst();
  }
}
