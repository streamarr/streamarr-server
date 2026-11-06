package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.MediaFileContainerInfo.MEDIA_FILE_CONTAINER_INFO;
import static com.streamarr.server.jooq.generated.tables.MediaFileLearnedStreamEnd.MEDIA_FILE_LEARNED_STREAM_END;
import static com.streamarr.server.repositories.media.StoredOutcomeConditions.snapshotMatches;

import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.jooq.generated.tables.MediaFileLearnedStreamEnd;
import com.streamarr.server.jooq.generated.tables.records.MediaFileLearnedStreamEndRecord;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JooqLearnedStreamEndRepository implements LearnedStreamEndRepository {

  private static final MediaFileLearnedStreamEnd LEARNED = MEDIA_FILE_LEARNED_STREAM_END;

  private final DSLContext dsl;

  // The media file's row lock, which probe publication also takes, makes the insert read the
  // stored outcome as it stands once any publication has committed. The end is stored only while
  // that outcome holds its snapshot, so a completion of an attempt that read an earlier version of
  // the file stores nothing. The first end learned for a snapshot wins against a concurrent
  // completion, and an end learned for an earlier version of the file is replaced.
  @Override
  @Transactional
  public boolean store(LearnedStreamEnd learned) {
    if (!lockMediaFile(learned.mediaFileId())) {
      return false;
    }

    var snapshot = learned.snapshot();
    var end = learned.endTime();
    return dsl.insertInto(
                LEARNED,
                LEARNED.MEDIA_FILE_ID,
                LEARNED.STREAM_INDEX,
                LEARNED.SOURCE_SIZE,
                LEARNED.SOURCE_MODIFIED_EPOCH_SECOND,
                LEARNED.SOURCE_MODIFIED_NANOS,
                LEARNED.END_SECONDS,
                LEARNED.END_NANOS)
            .select(
                DSL.select(
                        MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID,
                        DSL.val(learned.streamIndex()),
                        MEDIA_FILE_CONTAINER_INFO.SOURCE_SIZE,
                        MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_EPOCH_SECOND,
                        MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_NANOS,
                        DSL.val(end.getSeconds()),
                        DSL.val(end.getNano()))
                    .from(MEDIA_FILE_CONTAINER_INFO)
                    .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(learned.mediaFileId()))
                    .and(snapshotMatches(snapshot)))
            .onConflict(LEARNED.MEDIA_FILE_ID, LEARNED.STREAM_INDEX)
            .doUpdate()
            .set(LEARNED.SOURCE_SIZE, DSL.excluded(LEARNED.SOURCE_SIZE))
            .set(
                LEARNED.SOURCE_MODIFIED_EPOCH_SECOND,
                DSL.excluded(LEARNED.SOURCE_MODIFIED_EPOCH_SECOND))
            .set(LEARNED.SOURCE_MODIFIED_NANOS, DSL.excluded(LEARNED.SOURCE_MODIFIED_NANOS))
            .set(LEARNED.END_SECONDS, DSL.excluded(LEARNED.END_SECONDS))
            .set(LEARNED.END_NANOS, DSL.excluded(LEARNED.END_NANOS))
            .where(
                DSL.row(
                        LEARNED.SOURCE_SIZE,
                        LEARNED.SOURCE_MODIFIED_EPOCH_SECOND,
                        LEARNED.SOURCE_MODIFIED_NANOS)
                    .ne(
                        DSL.row(
                            DSL.excluded(LEARNED.SOURCE_SIZE),
                            DSL.excluded(LEARNED.SOURCE_MODIFIED_EPOCH_SECOND),
                            DSL.excluded(LEARNED.SOURCE_MODIFIED_NANOS))))
            .execute()
        == 1;
  }

  private boolean lockMediaFile(UUID mediaFileId) {
    return dsl.select(MEDIA_FILE.ID)
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.ID.eq(mediaFileId))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  @Override
  @Transactional(readOnly = true)
  public List<LearnedStreamEnd> findFor(UUID mediaFileId, SourceFileSnapshot snapshot) {
    return dsl.selectFrom(LEARNED)
        .where(LEARNED.MEDIA_FILE_ID.eq(mediaFileId))
        .and(LEARNED.SOURCE_SIZE.eq(snapshot.size()))
        .and(LEARNED.SOURCE_MODIFIED_EPOCH_SECOND.eq(snapshot.modifiedAt().getEpochSecond()))
        .and(LEARNED.SOURCE_MODIFIED_NANOS.eq(snapshot.modifiedAt().getNano()))
        .fetch(row -> learnedEnd(row, snapshot));
  }

  private static LearnedStreamEnd learnedEnd(
      MediaFileLearnedStreamEndRecord row, SourceFileSnapshot snapshot) {
    return LearnedStreamEnd.builder()
        .mediaFileId(row.getMediaFileId())
        .snapshot(snapshot)
        .streamIndex(row.getStreamIndex())
        .endTime(Duration.ofSeconds(row.getEndSeconds(), row.getEndNanos()))
        .build();
  }
}
