package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;

import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class SessionProgressRepositoryCustomImpl implements SessionProgressRepositoryCustom {

  private final DSLContext dsl;

  @Override
  public boolean upsertProgress(SaveWatchProgress progress) {
    var rowsAffected =
        dsl.insertInto(SESSION_PROGRESS)
            .set(SESSION_PROGRESS.SESSION_ID, progress.sessionId())
            .set(SESSION_PROGRESS.PROFILE_ID, progress.profileId())
            .set(SESSION_PROGRESS.MEDIA_FILE_ID, progress.mediaFileId())
            .set(SESSION_PROGRESS.POSITION_SECONDS, progress.positionSeconds())
            .set(SESSION_PROGRESS.PERCENT_COMPLETE, progress.percentComplete())
            .set(SESSION_PROGRESS.DURATION_SECONDS, progress.durationSeconds())
            .onConflict(SESSION_PROGRESS.SESSION_ID)
            .doUpdate()
            .set(SESSION_PROGRESS.POSITION_SECONDS, progress.positionSeconds())
            .set(SESSION_PROGRESS.PERCENT_COMPLETE, progress.percentComplete())
            .set(SESSION_PROGRESS.DURATION_SECONDS, progress.durationSeconds())
            .execute();
    return rowsAffected > 0;
  }

  @Override
  public void deleteBySessionId(UUID sessionId) {
    dsl.deleteFrom(SESSION_PROGRESS).where(SESSION_PROGRESS.SESSION_ID.eq(sessionId)).execute();
  }

  @Override
  public void deleteByProfileIdAndMediaFileIds(UUID profileId, Collection<UUID> mediaFileIds) {
    dsl.deleteFrom(SESSION_PROGRESS)
        .where(SESSION_PROGRESS.PROFILE_ID.eq(profileId))
        .and(SESSION_PROGRESS.MEDIA_FILE_ID.in(mediaFileIds))
        .execute();
  }

  @Override
  public void reassignProfile(UUID fromProfileId, UUID toProfileId) {
    dsl.update(SESSION_PROGRESS)
        .set(SESSION_PROGRESS.PROFILE_ID, toProfileId)
        .where(SESSION_PROGRESS.PROFILE_ID.eq(fromProfileId))
        .execute();
  }
}
