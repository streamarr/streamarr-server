package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.Tables.MEDIA_FILE_STREAM_INFO;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.probe.PersistedProbeReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Probe outcome publication")
class ProbeOutcomePublicationIT extends AbstractIntegrationTest {

  private static final SourceFileSnapshot SNAPSHOT_A =
      new SourceFileSnapshot(1234, Instant.parse("2026-09-10T10:00:00.123456789Z"));
  private static final SourceFileSnapshot SNAPSHOT_B =
      new SourceFileSnapshot(5678, Instant.parse("2026-09-11T10:00:00Z"));

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
  @DisplayName("Should persist container and stream rows when publishing a successful outcome")
  void shouldPersistContainerAndStreamRowsWhenPublishingASuccessfulOutcome() {
    var file = createMediaFile();
    var outcome = success("h264", "aac");

    var published = repository.publish(publication(file.getId(), SNAPSHOT_A, 1, outcome));

    assertThat(published).isTrue();
    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.snapshot()).isEqualTo(SNAPSHOT_A);
              assertThat(stored.probeVersion()).isEqualTo(1);
              assertThat(stored.outcome()).isEqualTo(outcome);
            });
    assertThat(streamRowCount(file.getId())).isEqualTo(2);
  }

  @Test
  @DisplayName("Should persist no stream rows when publishing a terminal error")
  void shouldPersistNoStreamRowsWhenPublishingATerminalError() {
    var file = createMediaFile();
    var failure = new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA);

    var published = repository.publish(publication(file.getId(), SNAPSHOT_A, 1, failure));

    assertThat(published).isTrue();
    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(failure));
    assertThat(streamRowCount(file.getId())).isZero();
  }

  @Test
  @DisplayName("Should replace an older compatible outcome when publishing a newer version")
  void shouldReplaceAnOlderCompatibleOutcomeWhenPublishingANewerVersion() {
    var file = createMediaFile();
    repository.publish(publication(file.getId(), SNAPSHOT_A, 1, success("h264", "aac")));
    var replacement = success("hevc", "eac3");

    var published = repository.publish(publication(file.getId(), SNAPSHOT_A, 2, replacement));

    assertThat(published).isTrue();
    assertThat(repository.findByMediaFileId(file.getId()))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.getProbeVersion()).isEqualTo(2);
              assertThat(stored.getOutcome()).isEqualTo(replacement);
            });
  }

  @Test
  @DisplayName("Should keep a newer outcome when an older version publishes the same snapshot")
  void shouldKeepANewerOutcomeWhenAnOlderVersionPublishesTheSameSnapshot() {
    var file = createMediaFile();
    var newer = success("hevc", "eac3");
    repository.publish(publication(file.getId(), SNAPSHOT_A, 2, newer));

    var published =
        repository.publish(publication(file.getId(), SNAPSHOT_A, 1, success("h264", "aac")));

    assertThat(published).isFalse();
    assertThat(repository.findByMediaFileId(file.getId()))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.getProbeVersion()).isEqualTo(2);
              assertThat(stored.getOutcome()).isEqualTo(newer);
            });
  }

  @Test
  @DisplayName("Should replace the outcome when the source snapshot differs from a newer version")
  void shouldReplaceTheOutcomeWhenTheSourceSnapshotDiffersFromANewerVersion() {
    var file = createMediaFile();
    repository.publish(publication(file.getId(), SNAPSHOT_A, 2, success("hevc", "eac3")));
    var replacement = success("h264", "aac");

    var published = repository.publish(publication(file.getId(), SNAPSHOT_B, 1, replacement));

    assertThat(published).isTrue();
    assertThat(repository.findByMediaFileId(file.getId()))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.getSnapshot()).isEqualTo(SNAPSHOT_B);
              assertThat(stored.getProbeVersion()).isEqualTo(1);
              assertThat(stored.getOutcome()).isEqualTo(replacement);
            });
  }

  @Test
  @DisplayName("Should replace stream rows when republishing the same snapshot and version")
  void shouldReplaceStreamRowsWhenRepublishingTheSameSnapshotAndVersion() {
    var file = createMediaFile();
    repository.publish(publication(file.getId(), SNAPSHOT_A, 1, success("h264", "aac")));
    var videoOnly = new ProbeOutcome.Success(container(), List.of(video("h264")));

    var published = repository.publish(publication(file.getId(), SNAPSHOT_A, 1, videoOnly));

    assertThat(published).isTrue();
    assertThat(streamRowCount(file.getId())).isEqualTo(1);
    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(videoOnly));
  }

  @Test
  @DisplayName("Should reject publication when the media file no longer exists")
  void shouldRejectPublicationWhenTheMediaFileNoLongerExists() {
    var missingMediaFileId = UUID.randomUUID();

    var published =
        repository.publish(publication(missingMediaFileId, SNAPSHOT_A, 1, success("h264", "aac")));

    assertThat(published).isFalse();
    assertThat(repository.findByMediaFileId(missingMediaFileId)).isEmpty();
  }

  private MediaFile createMediaFile() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var file =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("probe.mkv")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
                .size(1234)
                .build());
    createdFiles.add(file);
    return file;
  }

  private int streamRowCount(UUID mediaFileId) {
    return dsl.fetchCount(
        MEDIA_FILE_STREAM_INFO, MEDIA_FILE_STREAM_INFO.MEDIA_FILE_ID.eq(mediaFileId));
  }

  private static ProbePublication publication(
      UUID mediaFileId, SourceFileSnapshot snapshot, int probeVersion, ProbeOutcome outcome) {
    return ProbePublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(snapshot)
        .probeVersion(probeVersion)
        .outcome(outcome)
        .build();
  }

  private static ProbeOutcome.Success success(String videoCodec, String audioCodec) {
    return new ProbeOutcome.Success(container(), List.of(video(videoCodec), audio(audioCodec)));
  }

  private static ProbeContainer container() {
    return ProbeContainer.builder()
        .format(Optional.of("matroska,webm"))
        .duration(Optional.of(Duration.ofSeconds(123, 456)))
        .bitrate(OptionalLong.of(5_000_000))
        .build();
  }

  private static StreamInfo video(String codec) {
    return StreamInfo.builder()
        .index(0)
        .codecType("video")
        .codec(Optional.of(codec))
        .width(OptionalInt.of(1920))
        .height(OptionalInt.of(1080))
        .build();
  }

  private static StreamInfo audio(String codec) {
    return StreamInfo.builder()
        .index(1)
        .codecType("audio")
        .codec(Optional.of(codec))
        .channels(OptionalInt.of(6))
        .language(Optional.of("eng"))
        .build();
  }
}
