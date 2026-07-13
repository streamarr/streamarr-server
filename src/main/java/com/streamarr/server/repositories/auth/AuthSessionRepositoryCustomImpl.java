package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.AccountProfile.ACCOUNT_PROFILE;
import static com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION;
import static com.streamarr.server.jooq.generated.tables.HouseholdMembership.HOUSEHOLD_MEMBERSHIP;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.repositories.JooqQueryHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
  private final CounterChangePublisher counterChangePublisher;

  @PersistenceContext private final EntityManager entityManager;

  @Override
  public boolean hasLivePlaybackAuthority(PlaybackAuthority authority) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(AUTH_SESSION)
            .join(USER_ACCOUNT)
            .on(USER_ACCOUNT.ID.eq(AUTH_SESSION.ACCOUNT_ID))
            .join(HOUSEHOLD_MEMBERSHIP)
            .on(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(AUTH_SESSION.ACCOUNT_ID))
            .and(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID.eq(authority.householdId()))
            .join(ACCOUNT_PROFILE)
            .on(ACCOUNT_PROFILE.ACCOUNT_ID.eq(AUTH_SESSION.ACCOUNT_ID))
            .and(ACCOUNT_PROFILE.HOUSEHOLD_ID.eq(authority.householdId()))
            .and(ACCOUNT_PROFILE.PROFILE_ID.eq(authority.profileId()))
            .where(AUTH_SESSION.ID.eq(authority.authSessionId()))
            .and(AUTH_SESSION.ACCOUNT_ID.eq(authority.accountId()))
            .and(AUTH_SESSION.ACTIVE_HOUSEHOLD_ID.eq(authority.householdId()))
            .and(AUTH_SESSION.ACTIVE_PROFILE_ID.eq(authority.profileId()))
            .and(AUTH_SESSION.REVOKED_AT.isNull())
            .and(USER_ACCOUNT.ENABLED.isTrue()));
  }

  @Override
  @Transactional
  public Optional<Long> revoke(UUID sessionId, SessionRevocationReason reason, Instant now) {
    var nowOffset = now.atOffset(ZoneOffset.UTC);

    var updated =
        dsl.update(AUTH_SESSION)
            .set(AUTH_SESSION.REVOKED_AT, nowOffset)
            .set(
                AUTH_SESSION.REVOKED_REASON,
                com.streamarr.server.jooq.generated.enums.SessionRevocationReason.valueOf(
                    reason.name()))
            .set(AUTH_SESSION.SESSION_VERSION, AUTH_SESSION.SESSION_VERSION.plus(1))
            .set(AUTH_SESSION.LAST_MODIFIED_ON, nowOffset)
            .set(AUTH_SESSION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(AUTH_SESSION.ID.eq(sessionId))
            .and(AUTH_SESSION.REVOKED_AT.isNull())
            .returning(AUTH_SESSION.SESSION_VERSION)
            .fetchOne();

    var bumped = Optional.ofNullable(updated).map(row -> row.get(AUTH_SESSION.SESSION_VERSION));
    bumped.ifPresent(version -> counterChangePublisher.publishSession(sessionId, version));
    return bumped;
  }

  @Override
  @Transactional
  public Optional<Long> bumpVersion(UUID sessionId, Instant now) {
    var nowOffset = now.atOffset(ZoneOffset.UTC);

    var updated =
        dsl.update(AUTH_SESSION)
            .set(AUTH_SESSION.SESSION_VERSION, AUTH_SESSION.SESSION_VERSION.plus(1))
            .set(AUTH_SESSION.LAST_MODIFIED_ON, nowOffset)
            .set(AUTH_SESSION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(AUTH_SESSION.ID.eq(sessionId))
            .and(AUTH_SESSION.REVOKED_AT.isNull())
            .returning(AUTH_SESSION.SESSION_VERSION)
            .fetchOne();

    var bumped = Optional.ofNullable(updated).map(row -> row.get(AUTH_SESSION.SESSION_VERSION));
    bumped.ifPresent(version -> counterChangePublisher.publishSession(sessionId, version));
    return bumped;
  }

  @Override
  public boolean updateSelectionIfLive(AuthSession session, Instant now) {
    var nowOffset = now.atOffset(ZoneOffset.UTC);

    return dsl.update(AUTH_SESSION)
            .set(AUTH_SESSION.ACTIVE_HOUSEHOLD_ID, session.getActiveHouseholdId())
            .set(AUTH_SESSION.ACTIVE_PROFILE_ID, session.getActiveProfileId())
            .set(AUTH_SESSION.LAST_MODIFIED_ON, nowOffset)
            .set(AUTH_SESSION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(AUTH_SESSION.ID.eq(session.getId()))
            .and(AUTH_SESSION.REVOKED_AT.isNull())
            .execute()
        > 0;
  }

  @Override
  public Optional<AuthSession> lockById(UUID sessionId) {
    var query = dsl.selectFrom(AUTH_SESSION).where(AUTH_SESSION.ID.eq(sessionId)).forUpdate();

    return JooqQueryHelper.nativeQuery(entityManager, query, AuthSession.class).stream()
        .findFirst();
  }
}
