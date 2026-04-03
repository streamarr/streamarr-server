package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class SessionProgressRepositoryCustomImpl implements SessionProgressRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public void upsertProgress(SaveProgressCommand command) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    // insertIfAbsent — create row if no row for this session yet
    dsl.insertInto(SESSION_PROGRESS)
        .set(SESSION_PROGRESS.SESSION_ID, command.sessionId())
        .set(SESSION_PROGRESS.USER_ID, command.userId())
        .set(SESSION_PROGRESS.MEDIA_FILE_ID, command.mediaFileId())
        .set(SESSION_PROGRESS.POSITION_SECONDS, command.positionSeconds())
        .set(SESSION_PROGRESS.PERCENT_COMPLETE, command.percentComplete())
        .set(SESSION_PROGRESS.DURATION_SECONDS, command.durationSeconds())
        .set(SESSION_PROGRESS.CREATED_BY, auditUser)
        .set(SESSION_PROGRESS.LAST_MODIFIED_BY, auditUser)
        .onConflict(SESSION_PROGRESS.SESSION_ID)
        .doNothing()
        .execute();

    // Update the existing row with latest position
    dsl.update(SESSION_PROGRESS)
        .set(SESSION_PROGRESS.POSITION_SECONDS, command.positionSeconds())
        .set(SESSION_PROGRESS.PERCENT_COMPLETE, command.percentComplete())
        .set(SESSION_PROGRESS.DURATION_SECONDS, command.durationSeconds())
        .set(SESSION_PROGRESS.LAST_MODIFIED_BY, auditUser)
        .set(SESSION_PROGRESS.LAST_MODIFIED_ON, now)
        .where(SESSION_PROGRESS.SESSION_ID.eq(command.sessionId()))
        .execute();
  }

  @Override
  public void deleteBySessionId(UUID sessionId) {
    dsl.deleteFrom(SESSION_PROGRESS).where(SESSION_PROGRESS.SESSION_ID.eq(sessionId)).execute();
  }
}
