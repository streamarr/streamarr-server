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

  /**
   * Determines whether the playback authority is valid for an active session.
   *
   * @param authority the session, account, household, and profile identifiers to verify
   * @return {@code true} if the session is active, the account is enabled, and the profile has an active household share; {@code false} otherwise
   */
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

  /**
   * Revokes an active authentication session.
   *
   * @param sessionId the identifier of the session to revoke
   * @param reason    the reason for revocation
   * @param now       the revocation timestamp
   * @return {@code true} if the session was revoked, {@code false} otherwise
   */
  @Override
  @Transactional
  @SuppressWarnings("checkstyle:fullyQualifiedName")
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

  /**
   * Updates the active profile for an unrevoked authentication session.
   *
   * @param session the session whose active profile selection is updated
   * @param now the timestamp recorded as the modification time
   * @return {@code true} if a session was updated, {@code false} otherwise
   */
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

  /**
   * Clears the active profile selection from live sessions belonging to accounts in a household.
   *
   * @param profileId the profile whose selection is cleared
   * @param householdId the household associated with the accounts
   * @param now the timestamp recorded as the modification time
   * @return the number of updated sessions
   */
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

  /**
   * Clears active profile selections for all sessions belonging to an account.
   *
   * @param accountId the account whose session selections are cleared
   * @param now       the timestamp recorded as the modification time
   * @return the number of sessions updated
   */
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

  /**
   * Determines whether an authentication session exists for the specified identifier.
   *
   * @param sessionId the authentication session identifier
   * @return {@code true} if a matching session exists, {@code false} otherwise
   */
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
