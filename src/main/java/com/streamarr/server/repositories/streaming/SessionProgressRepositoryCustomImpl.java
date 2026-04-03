package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class SessionProgressRepositoryCustomImpl implements SessionProgressRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public boolean upsertProgress(SaveWatchProgress progress) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    var rowsAffected =
        dsl.insertInto(SESSION_PROGRESS)
            .set(SESSION_PROGRESS.SESSION_ID, progress.sessionId())
            .set(SESSION_PROGRESS.USER_ID, progress.userId())
            .set(SESSION_PROGRESS.MEDIA_FILE_ID, progress.mediaFileId())
            .set(SESSION_PROGRESS.POSITION_SECONDS, progress.positionSeconds())
            .set(SESSION_PROGRESS.PERCENT_COMPLETE, progress.percentComplete())
            .set(SESSION_PROGRESS.DURATION_SECONDS, progress.durationSeconds())
            .set(SESSION_PROGRESS.CREATED_ON, now)
            .set(SESSION_PROGRESS.CREATED_BY, auditUser)
            .set(SESSION_PROGRESS.LAST_MODIFIED_ON, now)
            .set(SESSION_PROGRESS.LAST_MODIFIED_BY, auditUser)
            .onConflict(SESSION_PROGRESS.SESSION_ID)
            .doUpdate()
            .set(SESSION_PROGRESS.POSITION_SECONDS, progress.positionSeconds())
            .set(SESSION_PROGRESS.PERCENT_COMPLETE, progress.percentComplete())
            .set(SESSION_PROGRESS.DURATION_SECONDS, progress.durationSeconds())
            .set(SESSION_PROGRESS.LAST_MODIFIED_BY, auditUser)
            .set(SESSION_PROGRESS.LAST_MODIFIED_ON, now)
            .execute();
    return rowsAffected > 0;
  }

  @Override
  public void deleteBySessionId(UUID sessionId) {
    dsl.deleteFrom(SESSION_PROGRESS).where(SESSION_PROGRESS.SESSION_ID.eq(sessionId)).execute();
  }

  @Override
  public void deleteByUserIdAndMediaFileIds(UUID userId, Collection<UUID> mediaFileIds) {
    dsl.deleteFrom(SESSION_PROGRESS)
        .where(SESSION_PROGRESS.USER_ID.eq(userId))
        .and(SESSION_PROGRESS.MEDIA_FILE_ID.in(mediaFileIds))
        .execute();
  }
}
