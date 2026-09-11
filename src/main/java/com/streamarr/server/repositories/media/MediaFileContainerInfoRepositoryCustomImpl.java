package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.Tables.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.Tables.MEDIA_FILE_CONTAINER_INFO;
import static com.streamarr.server.jooq.generated.Tables.MEDIA_FILE_STREAM_INFO;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.jooq.generated.tables.records.MediaFileStreamInfoRecord;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.SelectConditionStep;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class MediaFileContainerInfoRepositoryCustomImpl
    implements MediaFileContainerInfoRepositoryCustom {

  private final DSLContext dsl;

  @Override
  @Transactional
  public boolean publish(ProbePublication publication) {
    // The media file row lock serializes concurrent publications for one file; the FK cascade
    // makes a delete race impossible once the lock is held.
    if (!lockMediaFile(publication.mediaFileId())) {
      return false;
    }

    if (dsl.fetchExists(newerResultFor(publication))) {
      return false;
    }

    replaceOutcome(publication);
    return true;
  }

  private boolean lockMediaFile(UUID mediaFileId) {
    return dsl.select(MEDIA_FILE.ID)
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.ID.eq(mediaFileId))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  private SelectConditionStep<Record1<Integer>> newerResultFor(ProbePublication publication) {
    return dsl.selectOne()
        .from(MEDIA_FILE_CONTAINER_INFO)
        .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(publication.mediaFileId()))
        .and(snapshotMatches(publication.snapshot()))
        .and(MEDIA_FILE_CONTAINER_INFO.PROBE_VERSION.gt(publication.probeVersion()));
  }

  private static Condition snapshotMatches(SourceFileSnapshot snapshot) {
    return MEDIA_FILE_CONTAINER_INFO
        .SOURCE_SIZE
        .eq(snapshot.size())
        .and(
            MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_EPOCH_SECOND.eq(
                snapshot.modifiedAt().getEpochSecond()))
        .and(MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_NANOS.eq(snapshot.modifiedAt().getNano()));
  }

  private void replaceOutcome(ProbePublication publication) {
    var mediaFileId = publication.mediaFileId();
    dsl.deleteFrom(MEDIA_FILE_CONTAINER_INFO)
        .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(mediaFileId))
        .execute();

    var row = dsl.newRecord(MEDIA_FILE_CONTAINER_INFO);
    row.setMediaFileId(mediaFileId);
    row.setSourceSize(publication.snapshot().size());
    row.setSourceModifiedEpochSecond(publication.snapshot().modifiedAt().getEpochSecond());
    row.setSourceModifiedNanos(publication.snapshot().modifiedAt().getNano());
    row.setProbeVersion(publication.probeVersion());
    switch (publication.outcome()) {
      case ProbeOutcome.Failure(var error) -> row.setProbeError(error.name());
      case ProbeOutcome.Success(var container, _) -> {
        row.setFormat(container.format().orElse(null));
        row.setTotalBitrate(
            container.bitrate().isPresent() ? container.bitrate().getAsLong() : null);
        row.setDurationSeconds(container.duration().map(Duration::getSeconds).orElse(null));
        row.setDurationNanos(container.duration().map(Duration::getNano).orElse(null));
      }
    }

    dsl.executeInsert(row);
    if (publication.outcome() instanceof ProbeOutcome.Success success) {
      dsl.batchInsert(
              success.streams().stream().map(stream -> toStreamRow(mediaFileId, stream)).toList())
          .execute();
    }
  }

  private MediaFileStreamInfoRecord toStreamRow(UUID mediaFileId, StreamInfo stream) {
    var row = dsl.newRecord(MEDIA_FILE_STREAM_INFO);
    row.setMediaFileId(mediaFileId);
    row.setStreamIndex(stream.index());
    row.setCodecType(stream.codecType());
    row.setCodec(stream.codec().orElse(null));
    row.setWidth(stream.width().isPresent() ? stream.width().getAsInt() : null);
    row.setHeight(stream.height().isPresent() ? stream.height().getAsInt() : null);
    row.setFramerate(stream.framerate().isPresent() ? stream.framerate().getAsDouble() : null);
    row.setChannels(stream.channels().isPresent() ? stream.channels().getAsInt() : null);
    row.setBitrate(stream.bitrate().isPresent() ? stream.bitrate().getAsLong() : null);
    row.setLanguage(stream.language().orElse(null));
    row.setIsDefault(stream.isDefault());
    row.setIsForced(stream.isForced());
    return row;
  }
}
