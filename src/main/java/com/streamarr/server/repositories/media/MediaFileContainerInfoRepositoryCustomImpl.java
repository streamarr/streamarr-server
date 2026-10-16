package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.MediaFileChapter.MEDIA_FILE_CHAPTER;
import static com.streamarr.server.jooq.generated.tables.MediaFileContainerInfo.MEDIA_FILE_CONTAINER_INFO;
import static com.streamarr.server.jooq.generated.tables.MediaFileKeyframePositions.MEDIA_FILE_KEYFRAME_POSITIONS;
import static com.streamarr.server.jooq.generated.tables.MediaFileProbeTaskRequest.MEDIA_FILE_PROBE_TASK_REQUEST;
import static com.streamarr.server.jooq.generated.tables.MediaFileStreamInfo.MEDIA_FILE_STREAM_INFO;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.Chapter;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.domain.task.ProbeAttemptFailure;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.exceptions.MalformedKeyframePositionsException;
import com.streamarr.server.jooq.generated.enums.ItemResultFailureReason;
import com.streamarr.server.jooq.generated.tables.records.MediaFileChapterRecord;
import com.streamarr.server.jooq.generated.tables.records.MediaFileContainerInfoRecord;
import com.streamarr.server.jooq.generated.tables.records.MediaFileStreamInfoRecord;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.SelectConditionStep;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Propagation;
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

    if (requestedInputs(publication.mediaFileId())
        .filter(
            inputs ->
                !inputs.equals(new ProbeInputs(publication.snapshot(), publication.probeVersion())))
        .isPresent()) {
      return false;
    }

    replaceOutcome(publication);
    return true;
  }

  @Override
  @Transactional
  public boolean publishKeyframePacketScan(KeyframePacketScanPublication publication) {
    var changes = dsl.newRecord(MEDIA_FILE_CONTAINER_INFO);
    setKeyframeStatus(changes, publication.keyframes());
    changes.setKeyframePacketScanReason(null);
    var updated =
        dsl.update(MEDIA_FILE_CONTAINER_INFO)
            .set(changes)
            .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(publication.mediaFileId()))
            .and(snapshotMatches(publication.snapshot()))
            .and(MEDIA_FILE_CONTAINER_INFO.PROBE_VERSION.eq(publication.probeVersion()))
            .and(needsPacketScan())
            .execute();
    if (updated == 0) {
      return false;
    }

    if (publication.keyframes() instanceof KeyframeKnowledge.Known(var positions)) {
      insertKeyframePositions(publication.mediaFileId(), positions);
    }

    return true;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<ProbeInputs> lockPacketScanInputs(UUID mediaFileId) {
    if (!lockMediaFile(mediaFileId)) {
      return Optional.empty();
    }

    return dsl.select(
            MEDIA_FILE_CONTAINER_INFO.SOURCE_SIZE,
            MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_EPOCH_SECOND,
            MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_NANOS,
            MEDIA_FILE_CONTAINER_INFO.PROBE_VERSION)
        .from(MEDIA_FILE_CONTAINER_INFO)
        .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(mediaFileId))
        .and(needsPacketScan())
        .fetchOptional(
            row ->
                inputs(
                    row.value1(), Instant.ofEpochSecond(row.value2(), row.value3()), row.value4()));
  }

  private static Condition needsPacketScan() {
    return MEDIA_FILE_CONTAINER_INFO.KEYFRAME_STATUS.eq(KeyframeStatus.PACKET_SCAN_REQUIRED.name());
  }

  @Override
  @Transactional
  public boolean trySaveProbeRequest(UUID mediaFileId, ProbeInputs inputs) {
    if (!lockMediaFile(mediaFileId)) {
      return false;
    }

    clearFailure(mediaFileId, requestedInputsMatch(inputs).not());
    dsl.insertInto(MEDIA_FILE_PROBE_TASK_REQUEST)
        .set(MEDIA_FILE_PROBE_TASK_REQUEST.MEDIA_FILE_ID, mediaFileId)
        .set(MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_SIZE, inputs.snapshot().size())
        .set(
            MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_MODIFIED_EPOCH_SECOND,
            inputs.snapshot().modifiedAt().getEpochSecond())
        .set(
            MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_MODIFIED_NANOS,
            inputs.snapshot().modifiedAt().getNano())
        .set(MEDIA_FILE_PROBE_TASK_REQUEST.PROBE_VERSION, inputs.probeVersion())
        .onConflict(MEDIA_FILE_PROBE_TASK_REQUEST.MEDIA_FILE_ID)
        .doUpdate()
        .set(MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_SIZE, inputs.snapshot().size())
        .set(
            MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_MODIFIED_EPOCH_SECOND,
            inputs.snapshot().modifiedAt().getEpochSecond())
        .set(
            MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_MODIFIED_NANOS,
            inputs.snapshot().modifiedAt().getNano())
        .set(MEDIA_FILE_PROBE_TASK_REQUEST.PROBE_VERSION, inputs.probeVersion())
        .execute();
    invalidateOutcomeUnlessSnapshotMatches(mediaFileId, inputs.snapshot());
    return true;
  }

  @Override
  @Transactional
  public boolean trySaveProbeFailure(
      UUID mediaFileId, ProbeInputs inputs, ProbeAttemptFailure failure) {
    if (!lockMediaFile(mediaFileId)) {
      return false;
    }

    return dsl.update(MEDIA_FILE_PROBE_TASK_REQUEST)
            .set(
                MEDIA_FILE_PROBE_TASK_REQUEST.FAILURE_REASON,
                ItemResultFailureReason.lookupLiteral(failure.reason().name()))
            .set(MEDIA_FILE_PROBE_TASK_REQUEST.FAILURE_DETAIL, failure.detail())
            .set(
                MEDIA_FILE_PROBE_TASK_REQUEST.FAILED_AT,
                failure.failedAt().atOffset(ZoneOffset.UTC))
            .where(MEDIA_FILE_PROBE_TASK_REQUEST.MEDIA_FILE_ID.eq(mediaFileId))
            .and(requestedInputsMatch(inputs))
            .execute()
        > 0;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProbeState> findProbeStates(Collection<UUID> mediaFileIds) {
    return dsl.select(
            MEDIA_FILE.ID,
            MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_SIZE,
            MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_MODIFIED_EPOCH_SECOND,
            MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_MODIFIED_NANOS,
            MEDIA_FILE_PROBE_TASK_REQUEST.PROBE_VERSION,
            MEDIA_FILE_PROBE_TASK_REQUEST.FAILURE_REASON,
            MEDIA_FILE_PROBE_TASK_REQUEST.FAILURE_DETAIL,
            MEDIA_FILE_PROBE_TASK_REQUEST.FAILED_AT,
            MEDIA_FILE_CONTAINER_INFO.SOURCE_SIZE,
            MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_EPOCH_SECOND,
            MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_NANOS,
            MEDIA_FILE_CONTAINER_INFO.PROBE_VERSION,
            MEDIA_FILE_CONTAINER_INFO.PROBE_ERROR)
        .from(MEDIA_FILE)
        .leftJoin(MEDIA_FILE_PROBE_TASK_REQUEST)
        .on(MEDIA_FILE_PROBE_TASK_REQUEST.MEDIA_FILE_ID.eq(MEDIA_FILE.ID))
        .leftJoin(MEDIA_FILE_CONTAINER_INFO)
        .on(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(MEDIA_FILE.ID))
        .where(MEDIA_FILE.ID.eq(DSL.any(mediaFileIds.toArray(UUID[]::new))))
        .fetch(MediaFileContainerInfoRepositoryCustomImpl::toProbeState);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void withdrawProbeRequest(UUID mediaFileId) {
    dsl.deleteFrom(MEDIA_FILE_PROBE_TASK_REQUEST)
        .where(MEDIA_FILE_PROBE_TASK_REQUEST.MEDIA_FILE_ID.eq(mediaFileId))
        .execute();
  }

  @Override
  // A read changes nothing, so a malformed row must not mark the caller's transaction
  // rollback-only.
  @Transactional(readOnly = true, noRollbackFor = MalformedKeyframePositionsException.class)
  public Optional<KeyframeKnowledge> findKeyframeKnowledge(UUID mediaFileId) {
    return dsl.select(
            MEDIA_FILE_CONTAINER_INFO.KEYFRAME_STATUS,
            MEDIA_FILE_CONTAINER_INFO.KEYFRAME_PACKET_SCAN_REASON,
            MEDIA_FILE_CONTAINER_INFO.KEYFRAMES_UNAVAILABLE_REASON,
            MEDIA_FILE_KEYFRAME_POSITIONS.STREAM_INDEX,
            MEDIA_FILE_KEYFRAME_POSITIONS.TIME_BASE_NUMERATOR,
            MEDIA_FILE_KEYFRAME_POSITIONS.TIME_BASE_DENOMINATOR,
            MEDIA_FILE_KEYFRAME_POSITIONS.KEYFRAME_COUNT,
            MEDIA_FILE_KEYFRAME_POSITIONS.ENCODED_TIMES,
            MEDIA_FILE_KEYFRAME_POSITIONS.SOURCE)
        .from(MEDIA_FILE_CONTAINER_INFO)
        .leftJoin(MEDIA_FILE_KEYFRAME_POSITIONS)
        .on(MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID.eq(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID))
        .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(mediaFileId))
        .fetchOptional(MediaFileContainerInfoRepositoryCustomImpl::toKeyframeKnowledge);
  }

  private static KeyframeKnowledge toKeyframeKnowledge(Record row) {
    var status = row.get(MEDIA_FILE_CONTAINER_INFO.KEYFRAME_STATUS);
    if (status == null) {
      return new KeyframeKnowledge.NotReported();
    }

    return switch (KeyframeStatus.valueOf(status)) {
      case KNOWN -> new KeyframeKnowledge.Known(toKeyframePositions(row));
      case PACKET_SCAN_REQUIRED ->
          new KeyframeKnowledge.PacketScanRequired(
              KeyframePacketScanReason.valueOf(
                  row.get(MEDIA_FILE_CONTAINER_INFO.KEYFRAME_PACKET_SCAN_REASON)));
      case UNAVAILABLE ->
          new KeyframeKnowledge.Unavailable(
              KeyframesUnavailableReason.valueOf(
                  row.get(MEDIA_FILE_CONTAINER_INFO.KEYFRAMES_UNAVAILABLE_REASON)));
    };
  }

  private static KeyframePositions toKeyframePositions(Record row) {
    var keyframes =
        KeyframePositionsCodec.decode(row.get(MEDIA_FILE_KEYFRAME_POSITIONS.ENCODED_TIMES));
    if (keyframes.size() != row.get(MEDIA_FILE_KEYFRAME_POSITIONS.KEYFRAME_COUNT)) {
      throw new MalformedKeyframePositionsException(
          "Stored keyframe positions differ from their keyframe count");
    }

    return KeyframePositions.builder()
        .streamIndex(row.get(MEDIA_FILE_KEYFRAME_POSITIONS.STREAM_INDEX))
        .timeBaseNumerator(row.get(MEDIA_FILE_KEYFRAME_POSITIONS.TIME_BASE_NUMERATOR))
        .timeBaseDenominator(row.get(MEDIA_FILE_KEYFRAME_POSITIONS.TIME_BASE_DENOMINATOR))
        .source(KeyframePositionSource.valueOf(row.get(MEDIA_FILE_KEYFRAME_POSITIONS.SOURCE)))
        .keyframes(keyframes)
        .build();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, ChapterKnowledge> findChapterKnowledge(Collection<UUID> mediaFileIds) {
    // One statement reads each outcome's status with its chapters, so a publication between two
    // reads cannot pair one outcome's status with another outcome's chapters.
    var rows =
        dsl.select(
                MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID,
                MEDIA_FILE_CONTAINER_INFO.CHAPTER_STATUS,
                MEDIA_FILE_CHAPTER.CHAPTER_INDEX,
                MEDIA_FILE_CHAPTER.START_SECONDS,
                MEDIA_FILE_CHAPTER.START_NANOS,
                MEDIA_FILE_CHAPTER.END_SECONDS,
                MEDIA_FILE_CHAPTER.END_NANOS,
                MEDIA_FILE_CHAPTER.TITLE)
            .from(MEDIA_FILE_CONTAINER_INFO)
            .leftJoin(MEDIA_FILE_CHAPTER)
            .on(MEDIA_FILE_CHAPTER.MEDIA_FILE_ID.eq(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID))
            .where(
                MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(
                    DSL.any(mediaFileIds.toArray(UUID[]::new))))
            .orderBy(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID, MEDIA_FILE_CHAPTER.CHAPTER_INDEX)
            .fetch();
    var statuses = new LinkedHashMap<UUID, String>();
    var chapters = new LinkedHashMap<UUID, List<Chapter>>();
    for (var row : rows) {
      var mediaFileId = row.get(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID);
      statuses.put(mediaFileId, row.get(MEDIA_FILE_CONTAINER_INFO.CHAPTER_STATUS));
      var fileChapters = chapters.computeIfAbsent(mediaFileId, _ -> new ArrayList<>());
      if (row.get(MEDIA_FILE_CHAPTER.CHAPTER_INDEX) != null) {
        fileChapters.add(toChapter(row));
      }
    }

    var knowledge = new LinkedHashMap<UUID, ChapterKnowledge>();
    statuses.forEach(
        (mediaFileId, status) ->
            knowledge.put(mediaFileId, toChapterKnowledge(status, chapters.get(mediaFileId))));
    return knowledge;
  }

  private static ChapterKnowledge toChapterKnowledge(String status, List<Chapter> chapters) {
    if (status == null) {
      return new ChapterKnowledge.NotReported();
    }

    return switch (ChapterStatus.valueOf(status)) {
      case KNOWN -> new ChapterKnowledge.Known(chapters);
      case OMITTED -> new ChapterKnowledge.Omitted();
    };
  }

  private static Chapter toChapter(Record row) {
    return Chapter.builder()
        .startTime(
            toDuration(
                row.get(MEDIA_FILE_CHAPTER.START_SECONDS), row.get(MEDIA_FILE_CHAPTER.START_NANOS)))
        .endTime(
            toDuration(
                row.get(MEDIA_FILE_CHAPTER.END_SECONDS), row.get(MEDIA_FILE_CHAPTER.END_NANOS)))
        .title(Optional.ofNullable(row.get(MEDIA_FILE_CHAPTER.TITLE)))
        .build();
  }

  private static Optional<Duration> toDuration(Long seconds, Integer nanos) {
    if (seconds == null) {
      return Optional.empty();
    }

    return Optional.of(Duration.ofSeconds(seconds, nanos));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<ProbeInputs> lockProbeInputs(UUID mediaFileId) {
    if (!lockMediaFile(mediaFileId)) {
      return Optional.empty();
    }

    return requestedInputs(mediaFileId);
  }

  private Optional<ProbeInputs> requestedInputs(UUID mediaFileId) {
    return dsl.select(
            MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_SIZE,
            MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_MODIFIED_EPOCH_SECOND,
            MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_MODIFIED_NANOS,
            MEDIA_FILE_PROBE_TASK_REQUEST.PROBE_VERSION)
        .from(MEDIA_FILE_PROBE_TASK_REQUEST)
        .where(MEDIA_FILE_PROBE_TASK_REQUEST.MEDIA_FILE_ID.eq(mediaFileId))
        .fetchOptional()
        .flatMap(MediaFileContainerInfoRepositoryCustomImpl::toRequestedInputs);
  }

  private void clearFailure(UUID mediaFileId, Condition condition) {
    dsl.update(MEDIA_FILE_PROBE_TASK_REQUEST)
        .setNull(MEDIA_FILE_PROBE_TASK_REQUEST.FAILURE_REASON)
        .setNull(MEDIA_FILE_PROBE_TASK_REQUEST.FAILURE_DETAIL)
        .setNull(MEDIA_FILE_PROBE_TASK_REQUEST.FAILED_AT)
        .where(MEDIA_FILE_PROBE_TASK_REQUEST.MEDIA_FILE_ID.eq(mediaFileId))
        .and(condition)
        .execute();
  }

  private static Condition requestedInputsMatch(ProbeInputs inputs) {
    return MEDIA_FILE_PROBE_TASK_REQUEST
        .SOURCE_SIZE
        .eq(inputs.snapshot().size())
        .and(
            MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_MODIFIED_EPOCH_SECOND.eq(
                inputs.snapshot().modifiedAt().getEpochSecond()))
        .and(
            MEDIA_FILE_PROBE_TASK_REQUEST.SOURCE_MODIFIED_NANOS.eq(
                inputs.snapshot().modifiedAt().getNano()))
        .and(MEDIA_FILE_PROBE_TASK_REQUEST.PROBE_VERSION.eq(inputs.probeVersion()));
  }

  private static ProbeState toProbeState(Record row) {
    return ProbeState.builder()
        .mediaFileId(row.get(MEDIA_FILE.ID))
        .requested(toRequestedInputs(row))
        .stored(toStoredOutcome(row))
        .failure(toAttemptFailure(row))
        .build();
  }

  private static Optional<ProbeInputs> toRequestedInputs(Record row) {
    var request = MEDIA_FILE_PROBE_TASK_REQUEST;
    if (row.get(request.PROBE_VERSION) == null) {
      return Optional.empty();
    }

    return Optional.of(
        inputs(
            row.get(request.SOURCE_SIZE),
            Instant.ofEpochSecond(
                row.get(request.SOURCE_MODIFIED_EPOCH_SECOND),
                row.get(request.SOURCE_MODIFIED_NANOS)),
            row.get(request.PROBE_VERSION)));
  }

  private static Optional<ProbeState.Stored> toStoredOutcome(Record row) {
    var outcome = MEDIA_FILE_CONTAINER_INFO;
    if (row.get(outcome.PROBE_VERSION) == null) {
      return Optional.empty();
    }

    var inputs =
        inputs(
            row.get(outcome.SOURCE_SIZE),
            Instant.ofEpochSecond(
                row.get(outcome.SOURCE_MODIFIED_EPOCH_SECOND),
                row.get(outcome.SOURCE_MODIFIED_NANOS)),
            row.get(outcome.PROBE_VERSION));
    return Optional.of(
        new ProbeState.Stored(
            inputs, Optional.ofNullable(row.get(outcome.PROBE_ERROR)).map(ProbeError::valueOf)));
  }

  private static Optional<ProbeAttemptFailure> toAttemptFailure(Record row) {
    var request = MEDIA_FILE_PROBE_TASK_REQUEST;
    if (row.get(request.FAILURE_REASON) == null) {
      return Optional.empty();
    }

    return Optional.of(
        ProbeAttemptFailure.builder()
            .reason(ItemFailureReason.valueOf(row.get(request.FAILURE_REASON).getLiteral()))
            .detail(row.get(request.FAILURE_DETAIL))
            .failedAt(row.get(request.FAILED_AT).toInstant())
            .build());
  }

  private static ProbeInputs inputs(long size, Instant modifiedAt, int probeVersion) {
    return new ProbeInputs(new SourceFileSnapshot(size, modifiedAt), probeVersion);
  }

  private void invalidateOutcomeUnlessSnapshotMatches(
      UUID mediaFileId, SourceFileSnapshot snapshot) {
    dsl.deleteFrom(MEDIA_FILE_CONTAINER_INFO)
        .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(mediaFileId))
        .and(snapshotMatches(snapshot).not())
        .execute();
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
    clearFailure(mediaFileId, DSL.noCondition());
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
      case ProbeOutcome.Success(var container, _, var keyframes) -> {
        setKeyframeStatus(row, keyframes);
        setChapterStatus(row, container.chapters());
        row.setFormat(container.format().orElse(null));
        row.setTotalBitrate(
            container.bitrate().isPresent() ? container.bitrate().getAsLong() : null);
        row.setDurationSeconds(container.duration().map(Duration::getSeconds).orElse(null));
        row.setDurationNanos(container.duration().map(Duration::getNano).orElse(null));
      }
    }

    dsl.executeInsert(row);
    if (!(publication.outcome() instanceof ProbeOutcome.Success success)) {
      return;
    }

    dsl.batchInsert(
            success.streams().stream().map(stream -> toStreamRow(mediaFileId, stream)).toList())
        .execute();
    if (success.keyframes() instanceof KeyframeKnowledge.Known(var positions)) {
      insertKeyframePositions(mediaFileId, positions);
    }

    if (success.container().chapters() instanceof ChapterKnowledge.Known(var chapters)
        && !chapters.isEmpty()) {
      dsl.batchInsert(toChapterRows(mediaFileId, chapters)).execute();
    }
  }

  private static void setChapterStatus(
      MediaFileContainerInfoRecord row, ChapterKnowledge chapters) {
    var status =
        switch (chapters) {
          case ChapterKnowledge.Known _ -> ChapterStatus.KNOWN.name();
          case ChapterKnowledge.Omitted _ -> ChapterStatus.OMITTED.name();
          case ChapterKnowledge.NotReported _ -> null;
        };
    row.setChapterStatus(status);
  }

  private List<MediaFileChapterRecord> toChapterRows(UUID mediaFileId, List<Chapter> chapters) {
    var rows = new ArrayList<MediaFileChapterRecord>();
    for (var index = 0; index < chapters.size(); index++) {
      var chapter = chapters.get(index);
      var row = dsl.newRecord(MEDIA_FILE_CHAPTER);
      row.setMediaFileId(mediaFileId);
      row.setChapterIndex(index);
      row.setStartSeconds(chapter.startTime().map(Duration::getSeconds).orElse(null));
      row.setStartNanos(chapter.startTime().map(Duration::getNano).orElse(null));
      row.setEndSeconds(chapter.endTime().map(Duration::getSeconds).orElse(null));
      row.setEndNanos(chapter.endTime().map(Duration::getNano).orElse(null));
      row.setTitle(chapter.title().orElse(null));
      rows.add(row);
    }

    return rows;
  }

  private static void setKeyframeStatus(
      MediaFileContainerInfoRecord row, KeyframeKnowledge keyframes) {
    switch (keyframes) {
      case KeyframeKnowledge.Known _ -> row.setKeyframeStatus(KeyframeStatus.KNOWN.name());
      case KeyframeKnowledge.PacketScanRequired(var reason) -> {
        row.setKeyframeStatus(KeyframeStatus.PACKET_SCAN_REQUIRED.name());
        row.setKeyframePacketScanReason(reason.name());
      }
      case KeyframeKnowledge.Unavailable(var reason) -> {
        row.setKeyframeStatus(KeyframeStatus.UNAVAILABLE.name());
        row.setKeyframesUnavailableReason(reason.name());
      }
      case KeyframeKnowledge.NotReported _ -> row.setKeyframeStatus(null);
    }
  }

  private void insertKeyframePositions(UUID mediaFileId, KeyframePositions positions) {
    dsl.insertInto(MEDIA_FILE_KEYFRAME_POSITIONS)
        .set(MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID, mediaFileId)
        .set(MEDIA_FILE_KEYFRAME_POSITIONS.STREAM_INDEX, positions.streamIndex())
        .set(MEDIA_FILE_KEYFRAME_POSITIONS.TIME_BASE_NUMERATOR, positions.timeBaseNumerator())
        .set(MEDIA_FILE_KEYFRAME_POSITIONS.TIME_BASE_DENOMINATOR, positions.timeBaseDenominator())
        .set(MEDIA_FILE_KEYFRAME_POSITIONS.KEYFRAME_COUNT, positions.keyframes().size())
        .set(
            MEDIA_FILE_KEYFRAME_POSITIONS.ENCODED_TIMES,
            KeyframePositionsCodec.encode(positions.keyframes()))
        .set(MEDIA_FILE_KEYFRAME_POSITIONS.SOURCE, positions.source().name())
        .execute();
  }

  private enum KeyframeStatus {
    KNOWN,
    PACKET_SCAN_REQUIRED,
    UNAVAILABLE
  }

  private enum ChapterStatus {
    KNOWN,
    OMITTED
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
