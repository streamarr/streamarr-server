package com.streamarr.server.repositories;

import static com.streamarr.server.fixtures.StreamMeasurementFixture.measured;
import static com.streamarr.server.fixtures.StreamMeasurementFixture.measurement;
import static com.streamarr.server.fixtures.StreamMeasurementFixture.positions;
import static com.streamarr.server.jooq.generated.tables.MediaFileMeasuredStream.MEDIA_FILE_MEASURED_STREAM;
import static com.streamarr.server.jooq.generated.tables.MediaFileStreamMeasurement.MEDIA_FILE_STREAM_MEASUREMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.PersistedProbeOutcome;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.KeyframeIntervalCountsCodec;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Stream Measurement Publication Integration Tests")
class StreamMeasurementPublicationIT extends AbstractIntegrationTest {

  private static final SourceFileSnapshot SNAPSHOT_A =
      new SourceFileSnapshot(1234, Instant.parse("2026-10-30T10:00:00Z"));
  private static final SourceFileSnapshot SNAPSHOT_B =
      new SourceFileSnapshot(5678, Instant.parse("2026-10-31T10:00:00Z"));

  @Autowired private MediaFileContainerInfoRepository repository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private PersistedProbeReader reader;
  @Autowired private ProbeTaskRequests probeTaskRequests;
  @Autowired private DSLContext dsl;

  private final List<MediaFile> createdFiles = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    // Scheduler tests in later classes count every queued probe, so none of these may stay queued.
    dsl.deleteFrom(DSL.table("scheduled_tasks"))
        .where(
            DSL.field("task_instance", String.class)
                .in(createdFiles.stream().map(file -> file.getId().toString()).toList()))
        .execute();
    mediaFileRepository.deleteAll(createdFiles);
    createdFiles.clear();
  }

  @Test
  @DisplayName(
      "Should give playback each stream's measurement when a probe publishes a measurement")
  void shouldGivePlaybackEachStreamsMeasurementWhenProbePublishesMeasurement() {
    var file = createMediaFile();

    repository.publish(publication(file, measured(positions(90_000))));

    assertThat(stored(file).outcome())
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            success ->
                assertThat(success.mediaProbe().streamMeasurements()).contains(measurement()));
  }

  @Test
  @DisplayName("Should delete the measurement when a probe request names a changed source")
  void shouldDeleteMeasurementWhenProbeRequestNamesChangedSource() {
    var file = createMediaFile();
    repository.publish(publication(file, measured(positions(90_000))));

    probeTaskRequests.request(
        ProbeTaskRequest.builder()
            .mediaFileId(file.getId())
            .libraryId(file.getLibraryId())
            .filepathUri(file.getFilepathUri())
            .snapshot(SNAPSHOT_B)
            .probeVersion(ProbeVersion.CURRENT)
            .build());

    assertThat(measurementRowCount(file)).isZero();
    assertThat(measuredStreamRowCount(file)).isZero();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("otherSnapshotFields")
  @DisplayName(
      "Should read no measurement when the measurement read another snapshot than the stored"
          + " outcome")
  void shouldReadNoMeasurementWhenMeasurementReadAnotherSnapshotThanStoredOutcome(
      String field, Map<Field<?>, Object> otherSnapshot) {
    var file = createMediaFile();
    repository.publish(publication(file, measured(positions(90_000))));

    dsl.update(MEDIA_FILE_STREAM_MEASUREMENT)
        .set(otherSnapshot)
        .where(MEDIA_FILE_STREAM_MEASUREMENT.MEDIA_FILE_ID.eq(file.getId()))
        .execute();

    assertThat(measurementOf(file)).isEmpty();
  }

  static Stream<Arguments> otherSnapshotFields() {
    return Stream.of(
        arguments("size", Map.of(MEDIA_FILE_STREAM_MEASUREMENT.SOURCE_SIZE, SNAPSHOT_B.size())),
        arguments(
            "modification second",
            Map.of(
                MEDIA_FILE_STREAM_MEASUREMENT.SOURCE_MODIFIED_EPOCH_SECOND,
                SNAPSHOT_B.modifiedAt().getEpochSecond())),
        arguments(
            "modification nanosecond",
            Map.of(MEDIA_FILE_STREAM_MEASUREMENT.SOURCE_MODIFIED_NANOS, 1)));
  }

  @ParameterizedTest
  @ValueSource(
      ints = {StreamMeasurements.CURRENT_VERSION - 1, StreamMeasurements.CURRENT_VERSION + 1})
  @DisplayName(
      "Should read no measurement when the measurement has another measurement version than the"
          + " server's")
  void shouldReadNoMeasurementWhenMeasurementHasAnotherMeasurementVersionThanServers(
      int measurementVersion) {
    var file = createMediaFile();
    repository.publish(publication(file, measured(positions(90_000))));

    dsl.update(MEDIA_FILE_STREAM_MEASUREMENT)
        .set(MEDIA_FILE_STREAM_MEASUREMENT.MEASUREMENT_VERSION, measurementVersion)
        .where(MEDIA_FILE_STREAM_MEASUREMENT.MEDIA_FILE_ID.eq(file.getId()))
        .execute();

    assertThat(measurementOf(file)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the outcome without a measurement when stored interval bytes cover other"
          + " intervals than the interval count")
  void shouldKeepOutcomeWithoutMeasurementWhenStoredIntervalBytesCoverOtherIntervals() {
    var file = createMediaFile();
    repository.publish(publication(file, measured(positions(90_000))));

    dsl.update(MEDIA_FILE_MEASURED_STREAM)
        .set(
            MEDIA_FILE_MEASURED_STREAM.ENCODED_KEYFRAME_INTERVAL_BYTES,
            KeyframeIntervalCountsCodec.encode(KeyframeIntervalCounts.of(1, 2, 3)))
        .where(MEDIA_FILE_MEASURED_STREAM.MEDIA_FILE_ID.eq(file.getId()))
        .execute();

    assertThat(measurementOf(file)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the outcome without a measurement when stored interval packet counts cover"
          + " other intervals than the interval count")
  void shouldKeepOutcomeWithoutMeasurementWhenStoredIntervalPacketCountsCoverOtherIntervals() {
    var file = createMediaFile();
    repository.publish(publication(file, measured(positions(90_000))));

    dsl.update(MEDIA_FILE_MEASURED_STREAM)
        .set(
            MEDIA_FILE_MEASURED_STREAM.ENCODED_KEYFRAME_INTERVAL_PACKET_COUNTS,
            KeyframeIntervalCountsCodec.encode(KeyframeIntervalCounts.of(1, 2, 3)))
        .where(MEDIA_FILE_MEASURED_STREAM.MEDIA_FILE_ID.eq(file.getId()))
        .execute();

    assertThat(measurementOf(file)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the outcome without a measurement when stored interval bytes name an unknown"
          + " format")
  void shouldKeepOutcomeWithoutMeasurementWhenStoredIntervalBytesNameUnknownFormat() {
    var file = createMediaFile();
    repository.publish(publication(file, measured(positions(90_000))));

    dsl.update(MEDIA_FILE_MEASURED_STREAM)
        .set(MEDIA_FILE_MEASURED_STREAM.ENCODED_KEYFRAME_INTERVAL_BYTES, new byte[] {9})
        .where(MEDIA_FILE_MEASURED_STREAM.MEDIA_FILE_ID.eq(file.getId()))
        .execute();

    assertThat(measurementOf(file)).isEmpty();
  }

  @Test
  @DisplayName("Should delete the measurement when its media file is deleted")
  void shouldDeleteMeasurementWhenItsMediaFileIsDeleted() {
    var file = createMediaFile();
    repository.publish(publication(file, measured(positions(90_000))));

    mediaFileRepository.delete(file);
    createdFiles.remove(file);

    assertThat(measurementRowCount(file)).isZero();
    assertThat(measuredStreamRowCount(file)).isZero();
  }

  private Optional<StreamMeasurements> measurementOf(MediaFile file) {
    return switch (stored(file).outcome()) {
      case ProbeOutcome.Success success -> success.streamMeasurements();
      case ProbeOutcome.Failure _ -> Optional.empty();
    };
  }

  private PersistedProbeOutcome stored(MediaFile file) {
    return reader.find(file.getId()).orElseThrow();
  }

  private MediaFile createMediaFile() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var file =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("measured.mp4")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mp4")
                .size(SNAPSHOT_A.size())
                .build());
    createdFiles.add(file);
    return file;
  }

  private int measurementRowCount(MediaFile file) {
    return dsl.fetchCount(
        MEDIA_FILE_STREAM_MEASUREMENT,
        MEDIA_FILE_STREAM_MEASUREMENT.MEDIA_FILE_ID.eq(file.getId()));
  }

  private int measuredStreamRowCount(MediaFile file) {
    return dsl.fetchCount(
        MEDIA_FILE_MEASURED_STREAM, MEDIA_FILE_MEASURED_STREAM.MEDIA_FILE_ID.eq(file.getId()));
  }

  private static ProbePublication publication(MediaFile file, ProbeOutcome.Success outcome) {
    return ProbePublication.builder()
        .mediaFileId(file.getId())
        .snapshot(SNAPSHOT_A)
        .probeVersion(ProbeVersion.CURRENT)
        .outcome(outcome)
        .build();
  }
}
