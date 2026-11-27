package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFileLearnedStreamEnd.MEDIA_FILE_LEARNED_STREAM_END;

import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.jooq.generated.tables.MediaFileLearnedStreamEnd;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The stream end times that a keyframe packet scan read, stored among the learned stream end times
 * (ADR 0037, early ends). The scan read every packet of the stream, so its end time replaces one
 * that a job attempt learned for the same stream, and each row says that a scan read it. The caller
 * holds the media file's row lock and has verified that the stored probe outcome holds the
 * publication's snapshot.
 */
@RequiredArgsConstructor
final class ScannedStreamEndRows {

  private static final MediaFileLearnedStreamEnd LEARNED = MEDIA_FILE_LEARNED_STREAM_END;

  private final DSLContext dsl;

  void replaceWith(KeyframePacketScanPublication publication) {
    var snapshot = publication.snapshot();
    var rows =
        publication.streamEnds().stream()
            .map(
                end ->
                    DSL.row(
                        publication.mediaFileId(),
                        end.streamIndex(),
                        snapshot.size(),
                        snapshot.modifiedAt().getEpochSecond(),
                        snapshot.modifiedAt().getNano(),
                        end.endTime().getSeconds(),
                        end.endTime().getNano(),
                        true))
            .toList();
    if (rows.isEmpty()) {
      return;
    }

    dsl.insertInto(
            LEARNED,
            LEARNED.MEDIA_FILE_ID,
            LEARNED.STREAM_INDEX,
            LEARNED.SOURCE_SIZE,
            LEARNED.SOURCE_MODIFIED_EPOCH_SECOND,
            LEARNED.SOURCE_MODIFIED_NANOS,
            LEARNED.END_SECONDS,
            LEARNED.END_NANOS,
            LEARNED.FROM_PACKET_SCAN)
        .valuesOfRows(rows)
        .onConflict(LEARNED.MEDIA_FILE_ID, LEARNED.STREAM_INDEX)
        .doUpdate()
        .set(LEARNED.SOURCE_SIZE, DSL.excluded(LEARNED.SOURCE_SIZE))
        .set(
            LEARNED.SOURCE_MODIFIED_EPOCH_SECOND,
            DSL.excluded(LEARNED.SOURCE_MODIFIED_EPOCH_SECOND))
        .set(LEARNED.SOURCE_MODIFIED_NANOS, DSL.excluded(LEARNED.SOURCE_MODIFIED_NANOS))
        .set(LEARNED.END_SECONDS, DSL.excluded(LEARNED.END_SECONDS))
        .set(LEARNED.END_NANOS, DSL.excluded(LEARNED.END_NANOS))
        .set(LEARNED.FROM_PACKET_SCAN, DSL.excluded(LEARNED.FROM_PACKET_SCAN))
        .execute();
  }
}
