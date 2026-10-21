package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static com.streamarr.server.jooq.generated.tables.WatchHistory.WATCH_HISTORY;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.row;

import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class SessionProgressRepositoryCustomImpl implements SessionProgressRepositoryCustom {

  private final DSLContext dsl;
  private final StreamSessionLock streamSessionLock;
  private final EntityManager entityManager;

  @Override
  public List<Row<SessionProgress>> rowsBeyond(UUID profileId, Seek seek) {
    var keyset =
        Keyset.<OffsetDateTime>builder()
            .sortValue(SESSION_PROGRESS.LAST_MODIFIED_ON)
            .itemId(SESSION_PROGRESS.ID)
            .build();
    var query =
        keyset.rowsBeyond(
            keyset.selectFrom(dsl, SESSION_PROGRESS),
            SESSION_PROGRESS.PROFILE_ID.eq(profileId),
            seek);
    return JooqQueryHelper.nativeRows(entityManager, query, SessionProgress.class, keyset);
  }

  @Override
  public boolean upsertProgress(SaveWatchProgress progress) {
    streamSessionLock.lock(progress.sessionId());
    if (hasCompletion(progress.sessionId())) {
      return false;
    }

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
            .where(reportChangesProgress())
            .execute();
    return rowsAffected > 0;
  }

  // A repeated report leaves the row and its last-modified time alone. The time then still tells
  // which of two devices moved the position last.
  private static Condition reportChangesProgress() {
    return row(
            SESSION_PROGRESS.POSITION_SECONDS,
            SESSION_PROGRESS.PERCENT_COMPLETE,
            SESSION_PROGRESS.DURATION_SECONDS)
        .isDistinctFrom(
            row(
                excluded(SESSION_PROGRESS.POSITION_SECONDS),
                excluded(SESSION_PROGRESS.PERCENT_COMPLETE),
                excluded(SESSION_PROGRESS.DURATION_SECONDS)));
  }

  private boolean hasCompletion(UUID sessionId) {
    return dsl.fetchExists(WATCH_HISTORY, WATCH_HISTORY.STREAM_SESSION_ID.eq(sessionId));
  }

  @Override
  public int deleteBySessionId(UUID sessionId) {
    streamSessionLock.lock(sessionId);
    return dsl.deleteFrom(SESSION_PROGRESS)
        .where(SESSION_PROGRESS.SESSION_ID.eq(sessionId))
        .execute();
  }

  @Override
  public int deleteByProfileIdAndMediaFileIds(UUID profileId, Collection<UUID> mediaFileIds) {
    return dsl.deleteFrom(SESSION_PROGRESS)
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
