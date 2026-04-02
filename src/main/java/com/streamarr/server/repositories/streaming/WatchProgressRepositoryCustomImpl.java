package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.WatchProgress.WATCH_PROGRESS;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class WatchProgressRepositoryCustomImpl implements WatchProgressRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public boolean upsertProgress(SaveProgressCommand command) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var lastPlayedAtOdt =
        switch (command) {
          case SaveProgressCommand.MarkWatched mw -> mw.watchedAt().atOffset(ZoneOffset.UTC);
          case SaveProgressCommand.UpdateProgress _ -> null;
        };

    return dsl.insertInto(WATCH_PROGRESS)
            .set(WATCH_PROGRESS.USER_ID, command.userId())
            .set(WATCH_PROGRESS.MEDIA_FILE_ID, command.mediaFileId())
            .set(WATCH_PROGRESS.POSITION_SECONDS, command.positionSeconds())
            .set(WATCH_PROGRESS.PERCENT_COMPLETE, command.percentComplete())
            .set(WATCH_PROGRESS.DURATION_SECONDS, command.durationSeconds())
            .set(WATCH_PROGRESS.LAST_PLAYED_AT, lastPlayedAtOdt)
            .set(WATCH_PROGRESS.CREATED_BY, auditUser)
            .set(WATCH_PROGRESS.LAST_MODIFIED_BY, auditUser)
            .onConflict(WATCH_PROGRESS.USER_ID, WATCH_PROGRESS.MEDIA_FILE_ID)
            .doUpdate()
            .set(WATCH_PROGRESS.POSITION_SECONDS, command.positionSeconds())
            .set(WATCH_PROGRESS.PERCENT_COMPLETE, command.percentComplete())
            .set(WATCH_PROGRESS.DURATION_SECONDS, command.durationSeconds())
            .set(WATCH_PROGRESS.LAST_PLAYED_AT, lastPlayedAtOdt)
            .set(WATCH_PROGRESS.LAST_MODIFIED_BY, auditUser)
            .set(WATCH_PROGRESS.LAST_MODIFIED_ON, now)
            .where(WATCH_PROGRESS.LAST_PLAYED_AT.isNull())
            .execute()
        > 0;
  }

  @Override
  public boolean deleteIfNotWatched(UUID userId, UUID mediaFileId) {
    return dsl.deleteFrom(WATCH_PROGRESS)
            .where(WATCH_PROGRESS.USER_ID.eq(userId))
            .and(WATCH_PROGRESS.MEDIA_FILE_ID.eq(mediaFileId))
            .and(WATCH_PROGRESS.LAST_PLAYED_AT.isNull())
            .execute()
        > 0;
  }
}
