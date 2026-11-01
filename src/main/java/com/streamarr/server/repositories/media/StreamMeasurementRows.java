package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFileContainerInfo.MEDIA_FILE_CONTAINER_INFO;
import static com.streamarr.server.jooq.generated.tables.MediaFileKeyframePositions.MEDIA_FILE_KEYFRAME_POSITIONS;
import static com.streamarr.server.jooq.generated.tables.MediaFileMeasuredStream.MEDIA_FILE_MEASURED_STREAM;
import static com.streamarr.server.jooq.generated.tables.MediaFileStreamMeasurement.MEDIA_FILE_STREAM_MEASUREMENT;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.jooq.generated.tables.records.MediaFileMeasuredStreamRecord;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Writes a media file's stream measurement rows (ADR 0054). */
@RequiredArgsConstructor
final class StreamMeasurementRows {

  enum Status {
    MEASURED
  }

  private final DSLContext dsl;

  void deleteUnlessSnapshotMatches(UUID mediaFileId, SourceFileSnapshot snapshot) {
    var measurement = MEDIA_FILE_STREAM_MEASUREMENT;
    dsl.deleteFrom(measurement)
        .where(measurement.MEDIA_FILE_ID.eq(mediaFileId))
        .and(
            measurement
                .SOURCE_SIZE
                .eq(snapshot.size())
                .and(
                    measurement.SOURCE_MODIFIED_EPOCH_SECOND.eq(
                        snapshot.modifiedAt().getEpochSecond()))
                .and(measurement.SOURCE_MODIFIED_NANOS.eq(snapshot.modifiedAt().getNano()))
                .not())
        .execute();
  }

  /**
   * Stores the measurement in place of the file's earlier one, only while the stored outcome and
   * its keyframe positions, which the measurement's intervals follow, meet {@code outcomeMatches}.
   * Returns whether it stored the measurement.
   */
  boolean replace(UUID mediaFileId, Condition outcomeMatches, StreamMeasurements measurements) {
    var measurement = MEDIA_FILE_STREAM_MEASUREMENT;
    dsl.deleteFrom(measurement).where(measurement.MEDIA_FILE_ID.eq(mediaFileId)).execute();
    var stored =
        dsl.insertInto(
                measurement,
                measurement.MEDIA_FILE_ID,
                measurement.SOURCE_SIZE,
                measurement.SOURCE_MODIFIED_EPOCH_SECOND,
                measurement.SOURCE_MODIFIED_NANOS,
                measurement.MEASUREMENT_VERSION,
                measurement.KEYFRAME_POSITIONS_DIGEST,
                measurement.STATUS,
                measurement.SOURCE)
            .select(
                dsl.select(
                        MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID,
                        MEDIA_FILE_CONTAINER_INFO.SOURCE_SIZE,
                        MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_EPOCH_SECOND,
                        MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_NANOS,
                        DSL.val(StreamMeasurements.CURRENT_VERSION),
                        MEDIA_FILE_KEYFRAME_POSITIONS.POSITIONS_DIGEST,
                        DSL.val(Status.MEASURED.name()),
                        DSL.val(measurements.source().name()))
                    .from(MEDIA_FILE_CONTAINER_INFO)
                    .join(MEDIA_FILE_KEYFRAME_POSITIONS)
                    .on(
                        MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID.eq(
                            MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID))
                    .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(mediaFileId))
                    .and(outcomeMatches))
            .execute();
    if (stored == 0) {
      return false;
    }

    dsl.batchInsert(
            measurements.streams().stream()
                .map(stream -> toMeasuredStreamRow(mediaFileId, stream))
                .toList())
        .execute();
    return true;
  }

  private MediaFileMeasuredStreamRecord toMeasuredStreamRow(
      UUID mediaFileId, MeasuredStream stream) {
    var row = dsl.newRecord(MEDIA_FILE_MEASURED_STREAM);
    row.setMediaFileId(mediaFileId);
    row.setStreamIndex(stream.streamIndex());
    row.setMeasuredBytes(stream.measuredBytes());
    row.setPacketCount(stream.packetCount());
    row.setTimeBaseNumerator(stream.timeBaseNumerator());
    row.setTimeBaseDenominator(stream.timeBaseDenominator());
    row.setMeasuredDuration(stream.measuredDuration());
    row.setKeyframeIntervalCount(stream.keyframeIntervalBytes().intervalCount());
    row.setEncodedKeyframeIntervalBytes(
        KeyframeIntervalCountsCodec.encode(stream.keyframeIntervalBytes()));
    row.setEncodedKeyframeIntervalPacketCounts(
        KeyframeIntervalCountsCodec.encode(stream.keyframeIntervalPacketCounts()));
    return row;
  }
}
