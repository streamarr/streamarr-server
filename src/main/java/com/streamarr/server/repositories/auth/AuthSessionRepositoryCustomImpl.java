package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
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
  public boolean isLive(UUID sessionId, UUID accountId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(AUTH_SESSION)
            .where(AUTH_SESSION.ID.eq(sessionId))
            .and(AUTH_SESSION.ACCOUNT_ID.eq(accountId))
            .and(AUTH_SESSION.REVOKED_AT.isNull()));
  }

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

  @Override
  public boolean updateSelectionIfLive(AuthSession session, Instant now) {
    var nowOffset = now.atOffset(ZoneOffset.UTC);

    return dsl.update(AUTH_SESSION)
            .set(AUTH_SESSION.CONTEXT_HOUSEHOLD_ID, session.getContextHouseholdId())
            .set(AUTH_SESSION.SELECTED_PROFILE_ID, session.getSelectedProfileId())
            .set(AUTH_SESSION.LAST_MODIFIED_ON, nowOffset)
            .set(AUTH_SESSION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(AUTH_SESSION.ID.eq(session.getId()))
            .and(AUTH_SESSION.REVOKED_AT.isNull())
            .execute()
        > 0;
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
