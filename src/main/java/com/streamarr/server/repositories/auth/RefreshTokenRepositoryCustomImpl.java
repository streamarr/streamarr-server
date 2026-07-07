package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.RefreshToken.REFRESH_TOKEN;

import com.streamarr.server.jooq.generated.enums.RefreshTokenStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class RefreshTokenRepositoryCustomImpl implements RefreshTokenRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public int consumeActiveToken(String digest, Instant now) {
    var nowOffset = now.atOffset(ZoneOffset.UTC);

    return dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.STATUS, RefreshTokenStatus.ROTATED)
        .set(REFRESH_TOKEN.ROTATED_AT, nowOffset)
        .set(REFRESH_TOKEN.LAST_MODIFIED_ON, nowOffset)
        .set(REFRESH_TOKEN.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
        .where(REFRESH_TOKEN.DIGEST.eq(digest))
        .and(REFRESH_TOKEN.STATUS.eq(RefreshTokenStatus.ACTIVE))
        .and(REFRESH_TOKEN.EXPIRES_AT.gt(nowOffset))
        .execute();
  }

  @Override
  public void revokeAllForSession(UUID sessionId) {
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.STATUS, RefreshTokenStatus.REVOKED)
        .set(REFRESH_TOKEN.LAST_MODIFIED_ON, Instant.now().atOffset(ZoneOffset.UTC))
        .set(REFRESH_TOKEN.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
        .where(REFRESH_TOKEN.SESSION_ID.eq(sessionId))
        .and(REFRESH_TOKEN.STATUS.ne(RefreshTokenStatus.REVOKED))
        .execute();
  }
}
