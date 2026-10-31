package com.streamarr.server.repositories;

import static com.streamarr.server.fixtures.StreamMeasurementFixture.measured;
import static com.streamarr.server.fixtures.StreamMeasurementFixture.measurement;
import static com.streamarr.server.fixtures.StreamMeasurementFixture.positions;
import static com.streamarr.server.jooq.generated.tables.MediaFileMeasuredStream.MEDIA_FILE_MEASURED_STREAM;
import static com.streamarr.server.jooq.generated.tables.MediaFileStreamMeasurement.MEDIA_FILE_STREAM_MEASUREMENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.PersistedProbeOutcome;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.probe.PersistedProbeReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
  @Autowired private DSLContext dsl;

  private final List<MediaFile> createdFiles = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    mediaFileRepository.deleteAll(createdFiles);
    createdFiles.clear();
  }

  @Test
  @DisplayName(
      "Should give playback each stream's measurement when a probe publishes a measurement")
  void shouldGivePlaybackEachStreamsMeasurementWhenProbePublishesMeasurement() {
    var file = createMediaFile();

    repository.publish(publication(file, ProbeVersion.CURRENT, measured(positions(90_000))));

    assertThat(stored(file).outcome())
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            success ->
                assertThat(success.mediaProbe().streamMeasurements()).contains(measurement()));
  }

  @Test
  @DisplayName("Should delete the measurement when the source snapshot changes")
  void shouldDeleteMeasurementWhenSourceSnapshotChanges() {
    var file = createMediaFile();
    repository.publish(publication(file, ProbeVersion.CURRENT, measured(positions(90_000))));

    repository.trySaveProbeRequest(file.getId(), new ProbeInputs(SNAPSHOT_B, ProbeVersion.CURRENT));

    assertThat(measurementRowCount(file)).isZero();
    assertThat(measuredStreamRowCount(file)).isZero();
  }

  @Test
  @DisplayName(
      "Should read no measurement when the measurement read another snapshot than the stored"
          + " outcome")
  void shouldReadNoMeasurementWhenMeasurementReadAnotherSnapshotThanStoredOutcome() {
    var file = createMediaFile();
    repository.publish(publication(file, ProbeVersion.CURRENT, measured(positions(90_000))));

    dsl.update(MEDIA_FILE_STREAM_MEASUREMENT)
        .set(MEDIA_FILE_STREAM_MEASUREMENT.SOURCE_SIZE, SNAPSHOT_B.size())
        .where(MEDIA_FILE_STREAM_MEASUREMENT.MEDIA_FILE_ID.eq(file.getId()))
        .execute();

    assertThat(measurementOf(file)).isEmpty();
  }

  @Test
  @DisplayName("Should read no measurement when the measurement has an older measurement version")
  void shouldReadNoMeasurementWhenMeasurementHasOlderMeasurementVersion() {
    var file = createMediaFile();
    repository.publish(publication(file, ProbeVersion.CURRENT, measured(positions(90_000))));

    dsl.update(MEDIA_FILE_STREAM_MEASUREMENT)
        .set(
            MEDIA_FILE_STREAM_MEASUREMENT.MEASUREMENT_VERSION,
            StreamMeasurements.CURRENT_VERSION - 1)
        .where(MEDIA_FILE_STREAM_MEASUREMENT.MEDIA_FILE_ID.eq(file.getId()))
        .execute();

    assertThat(measurementOf(file)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the outcome without a measurement when a stored stream cannot be decoded")
  void shouldKeepOutcomeWithoutMeasurementWhenStoredStreamCannotBeDecoded() {
    var file = createMediaFile();
    repository.publish(publication(file, ProbeVersion.CURRENT, measured(positions(90_000))));

    dsl.update(MEDIA_FILE_MEASURED_STREAM)
        .set(MEDIA_FILE_MEASURED_STREAM.KEYFRAME_INTERVAL_COUNT, 3)
        .where(MEDIA_FILE_MEASURED_STREAM.MEDIA_FILE_ID.eq(file.getId()))
        .execute();

    assertThat(measurementOf(file)).isEmpty();
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

  private static ProbePublication publication(
      MediaFile file, int probeVersion, ProbeOutcome.Success outcome) {
    return ProbePublication.builder()
        .mediaFileId(file.getId())
        .snapshot(SNAPSHOT_A)
        .probeVersion(probeVersion)
        .outcome(outcome)
        .build();
  }
}
