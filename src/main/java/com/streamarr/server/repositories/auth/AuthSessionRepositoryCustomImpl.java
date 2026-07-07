package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION;

import com.streamarr.server.domain.auth.SessionRevocationReason;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class AuthSessionRepositoryCustomImpl implements AuthSessionRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
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

    return Optional.ofNullable(updated).map(record -> record.get(AUTH_SESSION.SESSION_VERSION));
  }

  @Override
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

    return Optional.ofNullable(updated).map(record -> record.get(AUTH_SESSION.SESSION_VERSION));
  }
}
