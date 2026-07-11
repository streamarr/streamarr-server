package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.JooqQueryHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

  @PersistenceContext private final EntityManager entityManager;

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

  @Override
  public List<UUID> lockIdsByAccountIdOrderById(UUID accountId) {
    return dsl.select(AUTH_SESSION.ID)
        .from(AUTH_SESSION)
        .where(AUTH_SESSION.ACCOUNT_ID.eq(accountId))
        .orderBy(AUTH_SESSION.ID)
        .forUpdate()
        .fetch(AUTH_SESSION.ID);
  }
}
