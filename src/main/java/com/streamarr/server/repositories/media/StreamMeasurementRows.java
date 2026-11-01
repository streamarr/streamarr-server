package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFileBitrateScanChunk.MEDIA_FILE_BITRATE_SCAN_CHUNK;
import static com.streamarr.server.jooq.generated.tables.MediaFileContainerInfo.MEDIA_FILE_CONTAINER_INFO;
import static com.streamarr.server.jooq.generated.tables.MediaFileKeyframePositions.MEDIA_FILE_KEYFRAME_POSITIONS;
import static com.streamarr.server.jooq.generated.tables.MediaFileMeasuredStream.MEDIA_FILE_MEASURED_STREAM;
import static com.streamarr.server.jooq.generated.tables.MediaFileStreamMeasurement.MEDIA_FILE_STREAM_MEASUREMENT;

import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.StreamMeasurementUnavailableReason;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.jooq.generated.tables.records.MediaFileMeasuredStreamRecord;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.SelectConditionStep;
import org.jooq.impl.DSL;

/** Writes a media file's stream measurement rows and bitrate scan chunks (ADR 0054). */
@RequiredArgsConstructor
final class StreamMeasurementRows {

  enum Status {
    MEASURED,
    UNAVAILABLE
  }

  private final DSLContext dsl;

  /**
   * Stores the measurement in place of the file's earlier one, only while the stored outcome and
   * its keyframe positions, which the measurement's intervals follow, meet {@code outcomeMatches}.
   * Returns whether it stored the measurement.
   */
  boolean replace(UUID mediaFileId, Condition outcomeMatches, StreamMeasurements measurements) {
    var stored =
        replaceMeasurementRow(
            mediaFileId,
            outcomeMatches,
            DSL.val(Status.MEASURED.name()),
            DSL.val(measurements.source().name()),
            DSL.inline(null, String.class));
    if (!stored) {
      return false;
    }

    dsl.batchInsert(
            measurements.streams().stream()
                .map(stream -> toMeasuredStreamRow(mediaFileId, stream))
                .toList())
        .execute();
    return true;
  }

  /**
   * Marks the file's measurement unavailable in place of its earlier one, under the same guard as
   * {@link #replace}. Returns whether it stored the mark.
   */
  boolean markUnavailable(
      UUID mediaFileId, Condition outcomeMatches, StreamMeasurementUnavailableReason reason) {
    return replaceMeasurementRow(
        mediaFileId,
        outcomeMatches,
        DSL.val(Status.UNAVAILABLE.name()),
        DSL.inline(null, String.class),
        DSL.val(reason.name()));
  }

  /**
   * Deletes the file's measurement and bitrate scan chunks unless they follow the stored outcome's
   * snapshot and keyframe positions.
   */
  void deleteNotFollowingStoredOutcome(UUID mediaFileId) {
    var measurement = MEDIA_FILE_STREAM_MEASUREMENT;
    dsl.deleteFrom(measurement)
        .where(measurement.MEDIA_FILE_ID.eq(mediaFileId))
        .andNotExists(
            storedOutcomeFollowing(
                measurement.MEDIA_FILE_ID,
                measurement.SOURCE_SIZE,
                measurement.SOURCE_MODIFIED_EPOCH_SECOND,
                measurement.SOURCE_MODIFIED_NANOS,
                measurement.KEYFRAME_POSITIONS_DIGEST))
        .execute();
    var chunk = MEDIA_FILE_BITRATE_SCAN_CHUNK;
    dsl.deleteFrom(chunk)
        .where(chunk.MEDIA_FILE_ID.eq(mediaFileId))
        .andNotExists(
            storedOutcomeFollowing(
                chunk.MEDIA_FILE_ID,
                chunk.SOURCE_SIZE,
                chunk.SOURCE_MODIFIED_EPOCH_SECOND,
                chunk.SOURCE_MODIFIED_NANOS,
                chunk.KEYFRAME_POSITIONS_DIGEST))
        .execute();
  }

  private SelectConditionStep<Record1<Integer>> storedOutcomeFollowing(
      Field<UUID> mediaFileId,
      Field<Long> size,
      Field<Long> modifiedEpochSecond,
      Field<Integer> modifiedNanos,
      Field<byte[]> keyframePositionsDigest) {
    return storedOutcome(mediaFileId)
        .and(MEDIA_FILE_CONTAINER_INFO.SOURCE_SIZE.eq(size))
        .and(MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_EPOCH_SECOND.eq(modifiedEpochSecond))
        .and(MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_NANOS.eq(modifiedNanos))
        .and(MEDIA_FILE_KEYFRAME_POSITIONS.POSITIONS_DIGEST.eq(keyframePositionsDigest));
  }

  private SelectConditionStep<Record1<Integer>> storedOutcome(Field<UUID> mediaFileId) {
    return dsl.selectOne()
        .from(MEDIA_FILE_CONTAINER_INFO)
        .join(MEDIA_FILE_KEYFRAME_POSITIONS)
        .on(MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID.eq(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID))
        .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(mediaFileId));
  }

  // The earlier row stays when the guard fails, so a write that loses to newer inputs changes
  // nothing.
  private boolean replaceMeasurementRow(
      UUID mediaFileId,
      Condition outcomeMatches,
      Field<String> status,
      Field<String> source,
      Field<String> unavailableReason) {
    var measurement = MEDIA_FILE_STREAM_MEASUREMENT;
    dsl.deleteFrom(measurement)
        .where(measurement.MEDIA_FILE_ID.eq(mediaFileId))
        .andExists(storedOutcome(DSL.val(mediaFileId)).and(outcomeMatches))
        .execute();
    return dsl.insertInto(
                measurement,
                measurement.MEDIA_FILE_ID,
                measurement.SOURCE_SIZE,
                measurement.SOURCE_MODIFIED_EPOCH_SECOND,
                measurement.SOURCE_MODIFIED_NANOS,
                measurement.MEASUREMENT_VERSION,
                measurement.KEYFRAME_POSITIONS_DIGEST,
                measurement.STATUS,
                measurement.SOURCE,
                measurement.UNAVAILABLE_REASON)
            .select(
                dsl.select(
                        MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID,
                        MEDIA_FILE_CONTAINER_INFO.SOURCE_SIZE,
                        MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_EPOCH_SECOND,
                        MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_NANOS,
                        DSL.val(StreamMeasurements.CURRENT_VERSION),
                        MEDIA_FILE_KEYFRAME_POSITIONS.POSITIONS_DIGEST,
                        status,
                        source,
                        unavailableReason)
                    .from(MEDIA_FILE_CONTAINER_INFO)
                    .join(MEDIA_FILE_KEYFRAME_POSITIONS)
                    .on(
                        MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID.eq(
                            MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID))
                    .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(mediaFileId))
                    .and(outcomeMatches))
            .execute()
        > 0;
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
