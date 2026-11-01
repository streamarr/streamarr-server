package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.MediaFileBitrateScanChunk.MEDIA_FILE_BITRATE_SCAN_CHUNK;
import static com.streamarr.server.jooq.generated.tables.MediaFileBitrateScanChunkStream.MEDIA_FILE_BITRATE_SCAN_CHUNK_STREAM;
import static com.streamarr.server.jooq.generated.tables.MediaFileContainerInfo.MEDIA_FILE_CONTAINER_INFO;
import static com.streamarr.server.jooq.generated.tables.MediaFileKeyframePositions.MEDIA_FILE_KEYFRAME_POSITIONS;
import static com.streamarr.server.jooq.generated.tables.MediaFileStreamInfo.MEDIA_FILE_STREAM_INFO;
import static com.streamarr.server.jooq.generated.tables.MediaFileStreamMeasurement.MEDIA_FILE_STREAM_MEASUREMENT;
import static com.streamarr.server.repositories.media.StoredOutcomeConditions.snapshotMatches;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MeasuredChunkStream;
import com.streamarr.server.domain.streaming.StreamMeasurementUnavailableReason;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.BitrateScanChunkPublication;
import com.streamarr.server.domain.task.BitrateScanInputs;
import com.streamarr.server.domain.task.BitrateScanKey;
import com.streamarr.server.domain.task.StoredBitrateScanChunk;
import com.streamarr.server.domain.task.StreamMeasurementPublication;
import com.streamarr.server.jooq.generated.tables.records.MediaFileBitrateScanChunkStreamRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JooqBitrateScanRepository implements BitrateScanRepository {

  // The codec families that a fragmented MP4 HLS copy carries.
  private static final Set<String> COPYABLE_VIDEO_CODECS = Set.of("h264", "hevc", "av1");
  // ffprobe's format name for MP4 and MOV files.
  private static final String MP4_FORMAT_NAME = "mov,mp4,m4a,3gp,3g2,mj2";
  private static final HexFormat HEX = HexFormat.of();

  private final DSLContext dsl;
  private final StreamMeasurementRows measurementRows;

  public JooqBitrateScanRepository(DSLContext dsl) {
    this.dsl = dsl;
    this.measurementRows = new StreamMeasurementRows(dsl);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BitrateScanInputs> findBitrateScanInputs(UUID mediaFileId) {
    return readBitrateScanInputs(mediaFileId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<BitrateScanInputs> lockBitrateScanInputs(UUID mediaFileId) {
    var locked =
        dsl.select(MEDIA_FILE.ID)
            .from(MEDIA_FILE)
            .where(MEDIA_FILE.ID.eq(mediaFileId))
            .forUpdate()
            .fetchOptional();
    if (locked.isEmpty()) {
      return Optional.empty();
    }

    return readBitrateScanInputs(mediaFileId);
  }

  private Optional<BitrateScanInputs> readBitrateScanInputs(UUID mediaFileId) {
    var outcome = MEDIA_FILE_CONTAINER_INFO;
    var positions = MEDIA_FILE_KEYFRAME_POSITIONS;
    var chosen = MEDIA_FILE_STREAM_INFO;
    var row =
        dsl.select(
                outcome.SOURCE_SIZE,
                outcome.SOURCE_MODIFIED_EPOCH_SECOND,
                outcome.SOURCE_MODIFIED_NANOS,
                outcome.DURATION_SECONDS,
                outcome.DURATION_NANOS,
                positions.STREAM_INDEX,
                positions.TIME_BASE_NUMERATOR,
                positions.TIME_BASE_DENOMINATOR,
                positions.SOURCE,
                positions.ENCODED_TIMES,
                positions.POSITIONS_DIGEST,
                chosen.BITRATE)
            .from(outcome)
            .join(positions)
            .on(positions.MEDIA_FILE_ID.eq(outcome.MEDIA_FILE_ID))
            .join(chosen)
            .on(chosen.MEDIA_FILE_ID.eq(outcome.MEDIA_FILE_ID))
            .and(chosen.STREAM_INDEX.eq(positions.STREAM_INDEX))
            .where(outcome.MEDIA_FILE_ID.eq(mediaFileId))
            .and(needsBitrateScan())
            .fetchOptional();
    return row.map(found -> toInputs(found, audioStreamIndexes(mediaFileId)));
  }

  // ADR 0054's bitrate scan reads only files whose chosen video stream a copy could carry, and
  // never an MP4 or MOV file whose sample tables the probe read.
  private static Condition needsBitrateScan() {
    var outcome = MEDIA_FILE_CONTAINER_INFO;
    var positions = MEDIA_FILE_KEYFRAME_POSITIONS;
    var measurement = MEDIA_FILE_STREAM_MEASUREMENT;
    return outcome
        .PROBE_ERROR
        .isNull()
        .and(MEDIA_FILE_STREAM_INFO.CODEC.in(COPYABLE_VIDEO_CODECS))
        .and(
            outcome
                .FORMAT
                .isDistinctFrom(MP4_FORMAT_NAME)
                .or(positions.SOURCE.ne(KeyframePositionSource.CONTAINER_INDEX.name())))
        .andNotExists(
            DSL.selectOne()
                .from(measurement)
                .where(measurement.MEDIA_FILE_ID.eq(outcome.MEDIA_FILE_ID))
                .and(measurement.SOURCE_SIZE.eq(outcome.SOURCE_SIZE))
                .and(
                    measurement.SOURCE_MODIFIED_EPOCH_SECOND.eq(
                        outcome.SOURCE_MODIFIED_EPOCH_SECOND))
                .and(measurement.SOURCE_MODIFIED_NANOS.eq(outcome.SOURCE_MODIFIED_NANOS))
                .and(measurement.KEYFRAME_POSITIONS_DIGEST.eq(positions.POSITIONS_DIGEST))
                .and(measurement.MEASUREMENT_VERSION.eq(StreamMeasurements.CURRENT_VERSION)));
  }

  private List<Integer> audioStreamIndexes(UUID mediaFileId) {
    return dsl.select(MEDIA_FILE_STREAM_INFO.STREAM_INDEX)
        .from(MEDIA_FILE_STREAM_INFO)
        .where(MEDIA_FILE_STREAM_INFO.MEDIA_FILE_ID.eq(mediaFileId))
        .and(MEDIA_FILE_STREAM_INFO.CODEC_TYPE.eq("audio"))
        .orderBy(MEDIA_FILE_STREAM_INFO.STREAM_INDEX)
        .fetch(MEDIA_FILE_STREAM_INFO.STREAM_INDEX);
  }

  private static BitrateScanInputs toInputs(Record row, List<Integer> audioStreamIndexes) {
    var outcome = MEDIA_FILE_CONTAINER_INFO;
    var positions = MEDIA_FILE_KEYFRAME_POSITIONS;
    var snapshot =
        new SourceFileSnapshot(
            row.get(outcome.SOURCE_SIZE),
            Instant.ofEpochSecond(
                row.get(outcome.SOURCE_MODIFIED_EPOCH_SECOND),
                row.get(outcome.SOURCE_MODIFIED_NANOS)));
    var durationSeconds = row.get(outcome.DURATION_SECONDS);
    var duration =
        Optional.ofNullable(durationSeconds)
            .map(seconds -> Duration.ofSeconds(seconds, row.get(outcome.DURATION_NANOS)));
    return BitrateScanInputs.builder()
        .key(
            new BitrateScanKey(
                snapshot,
                HEX.formatHex(row.get(positions.POSITIONS_DIGEST)),
                StreamMeasurements.CURRENT_VERSION))
        .positions(
            KeyframePositions.builder()
                .streamIndex(row.get(positions.STREAM_INDEX))
                .timeBaseNumerator(row.get(positions.TIME_BASE_NUMERATOR))
                .timeBaseDenominator(row.get(positions.TIME_BASE_DENOMINATOR))
                .source(KeyframePositionSource.valueOf(row.get(positions.SOURCE)))
                .keyframes(KeyframePositionsCodec.decode(row.get(positions.ENCODED_TIMES)))
                .build())
        .containerDuration(duration)
        .audioStreamIndexes(audioStreamIndexes)
        .chosenVideoHasProbedBitrate(row.get(MEDIA_FILE_STREAM_INFO.BITRATE) != null)
        .build();
  }

  @Override
  @Transactional
  public boolean storeChunk(BitrateScanChunkPublication publication) {
    var chunk = MEDIA_FILE_BITRATE_SCAN_CHUNK;
    var key = publication.key();
    var stored = publication.chunk();
    var inserted =
        dsl.insertInto(
                chunk,
                chunk.MEDIA_FILE_ID,
                chunk.FIRST_KEYFRAME_INTERVAL,
                chunk.KEYFRAME_INTERVAL_COUNT,
                chunk.SOURCE_SIZE,
                chunk.SOURCE_MODIFIED_EPOCH_SECOND,
                chunk.SOURCE_MODIFIED_NANOS,
                chunk.MEASUREMENT_VERSION,
                chunk.KEYFRAME_POSITIONS_DIGEST)
            .select(
                dsl.select(
                        MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID,
                        DSL.val(stored.firstKeyframeInterval()),
                        DSL.val(stored.keyframeIntervalCount()),
                        MEDIA_FILE_CONTAINER_INFO.SOURCE_SIZE,
                        MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_EPOCH_SECOND,
                        MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_NANOS,
                        DSL.val(key.measurementVersion()),
                        MEDIA_FILE_KEYFRAME_POSITIONS.POSITIONS_DIGEST)
                    .from(MEDIA_FILE_CONTAINER_INFO)
                    .join(MEDIA_FILE_KEYFRAME_POSITIONS)
                    .on(
                        MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID.eq(
                            MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID))
                    .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(publication.mediaFileId()))
                    .and(storedOutcomeFollows(key)))
            .onConflictDoNothing()
            .execute();
    if (inserted == 0) {
      return false;
    }

    dsl.batchInsert(
            stored.streams().stream()
                .map(
                    stream ->
                        toChunkStreamRow(
                            publication.mediaFileId(), stored.firstKeyframeInterval(), stream))
                .toList())
        .execute();
    return true;
  }

  private MediaFileBitrateScanChunkStreamRecord toChunkStreamRow(
      UUID mediaFileId, int firstKeyframeInterval, MeasuredChunkStream stream) {
    var row = dsl.newRecord(MEDIA_FILE_BITRATE_SCAN_CHUNK_STREAM);
    row.setMediaFileId(mediaFileId);
    row.setFirstKeyframeInterval(firstKeyframeInterval);
    row.setStreamIndex(stream.streamIndex());
    row.setMeasuredBytes(stream.measuredBytes());
    row.setPacketCount(stream.packetCount());
    row.setTimeBaseNumerator(stream.timeBaseNumerator());
    row.setTimeBaseDenominator(stream.timeBaseDenominator());
    stream
        .packetTimes()
        .ifPresent(
            times -> {
              row.setFirstPresentationTime(times.firstPresentationTime());
              row.setEndTime(times.endTime());
            });
    row.setEncodedKeyframeIntervalBytes(
        KeyframeIntervalCountsCodec.encode(stream.keyframeIntervalBytes()));
    row.setEncodedKeyframeIntervalPacketCounts(
        KeyframeIntervalCountsCodec.encode(stream.keyframeIntervalPacketCounts()));
    return row;
  }

  @Override
  @Transactional(readOnly = true)
  public List<StoredBitrateScanChunk> findChunks(UUID mediaFileId, BitrateScanKey key) {
    var chunk = MEDIA_FILE_BITRATE_SCAN_CHUNK;
    var streams = MEDIA_FILE_BITRATE_SCAN_CHUNK_STREAM;
    var rows =
        dsl.select(chunk.KEYFRAME_INTERVAL_COUNT, streams.asterisk())
            .from(chunk)
            .join(streams)
            .on(streams.MEDIA_FILE_ID.eq(chunk.MEDIA_FILE_ID))
            .and(streams.FIRST_KEYFRAME_INTERVAL.eq(chunk.FIRST_KEYFRAME_INTERVAL))
            .where(chunk.MEDIA_FILE_ID.eq(mediaFileId))
            .and(chunkFollows(key))
            .orderBy(chunk.FIRST_KEYFRAME_INTERVAL, streams.STREAM_INDEX)
            .fetch();
    // jOOQ keeps the groups in the order of the rows.
    return rows.intoGroups(chunk.FIRST_KEYFRAME_INTERVAL).entrySet().stream()
        .map(
            group ->
                StoredBitrateScanChunk.builder()
                    .firstKeyframeInterval(group.getKey())
                    .keyframeIntervalCount(
                        group.getValue().getFirst().get(chunk.KEYFRAME_INTERVAL_COUNT))
                    .streams(
                        group.getValue().stream()
                            .map(row -> toMeasuredChunkStream(row.into(streams)))
                            .toList())
                    .build())
        .toList();
  }

  private static MeasuredChunkStream toMeasuredChunkStream(
      MediaFileBitrateScanChunkStreamRecord row) {
    var packetTimes =
        Optional.ofNullable(row.getFirstPresentationTime())
            .map(first -> new MeasuredChunkStream.PacketTimes(first, row.getEndTime()));
    return MeasuredChunkStream.builder()
        .streamIndex(row.getStreamIndex())
        .measuredBytes(row.getMeasuredBytes())
        .packetCount(row.getPacketCount())
        .timeBaseNumerator(row.getTimeBaseNumerator())
        .timeBaseDenominator(row.getTimeBaseDenominator())
        .packetTimes(packetTimes)
        .keyframeIntervalBytes(
            KeyframeIntervalCountsCodec.decode(row.getEncodedKeyframeIntervalBytes()))
        .keyframeIntervalPacketCounts(
            KeyframeIntervalCountsCodec.decode(row.getEncodedKeyframeIntervalPacketCounts()))
        .build();
  }

  @Override
  @Transactional
  public void deleteChunksNotFollowing(UUID mediaFileId, BitrateScanKey key) {
    dsl.deleteFrom(MEDIA_FILE_BITRATE_SCAN_CHUNK)
        .where(MEDIA_FILE_BITRATE_SCAN_CHUNK.MEDIA_FILE_ID.eq(mediaFileId))
        .and(chunkFollows(key).not())
        .execute();
  }

  @Override
  @Transactional
  public void deleteChunks(UUID mediaFileId) {
    dsl.deleteFrom(MEDIA_FILE_BITRATE_SCAN_CHUNK)
        .where(MEDIA_FILE_BITRATE_SCAN_CHUNK.MEDIA_FILE_ID.eq(mediaFileId))
        .execute();
  }

  @Override
  @Transactional
  public boolean publishMeasurement(StreamMeasurementPublication publication) {
    if (publication.key().measurementVersion() != StreamMeasurements.CURRENT_VERSION) {
      return false;
    }

    return measurementRows.replace(
        publication.mediaFileId(),
        storedOutcomeFollows(publication.key()),
        publication.measurements());
  }

  @Override
  @Transactional
  public boolean markMeasurementUnavailable(
      UUID mediaFileId, BitrateScanKey key, StreamMeasurementUnavailableReason reason) {
    if (key.measurementVersion() != StreamMeasurements.CURRENT_VERSION) {
      return false;
    }

    return measurementRows.markUnavailable(mediaFileId, storedOutcomeFollows(key), reason);
  }

  private static Condition storedOutcomeFollows(BitrateScanKey key) {
    return snapshotMatches(key.snapshot())
        .and(
            MEDIA_FILE_KEYFRAME_POSITIONS.POSITIONS_DIGEST.eq(
                HEX.parseHex(key.keyframePositionsDigest())));
  }

  private static Condition chunkFollows(BitrateScanKey key) {
    var chunk = MEDIA_FILE_BITRATE_SCAN_CHUNK;
    var snapshot = key.snapshot();
    return chunk
        .SOURCE_SIZE
        .eq(snapshot.size())
        .and(chunk.SOURCE_MODIFIED_EPOCH_SECOND.eq(snapshot.modifiedAt().getEpochSecond()))
        .and(chunk.SOURCE_MODIFIED_NANOS.eq(snapshot.modifiedAt().getNano()))
        .and(chunk.KEYFRAME_POSITIONS_DIGEST.eq(HEX.parseHex(key.keyframePositionsDigest())))
        .and(chunk.MEASUREMENT_VERSION.eq(key.measurementVersion()));
  }
}
