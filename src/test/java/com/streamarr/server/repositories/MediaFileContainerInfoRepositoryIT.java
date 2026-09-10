package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.MediaFileContainerInfo.MediaFileContainerInfoBuilder;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaFileStreamId;
import com.streamarr.server.domain.media.MediaFileStreamInfo;
import com.streamarr.server.domain.media.PersistedProbeOutcome;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.probe.PersistedProbeReader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Tag("IntegrationTest")
@DisplayName("Persisted media file probe outcomes")
class MediaFileContainerInfoRepositoryIT extends AbstractIntegrationTest {

  @Autowired private MediaFileContainerInfoRepository repository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private PersistedProbeReader reader;

  @Test
  @DisplayName("Should return no outcome when the file has not been probed")
  void shouldReturnNoOutcomeWhenFileHasNotBeenProbed() {
    assertThat(reader.find(UUID.randomUUID())).isEmpty();
  }

  @Test
  @Transactional
  @DisplayName("Should retain the exact source snapshot and version when reading a probe outcome")
  void shouldRetainExactSourceSnapshotAndVersionWhenReadingProbeOutcome() {
    var file = createMediaFile();
    var snapshot = new SourceFileSnapshot(1234, Instant.parse("2026-09-10T10:00:00.999999999Z"));
    entityManager.persist(outcomeBuilder(file.getId()).snapshot(snapshot).build());
    flushAndClear();

    assertThat(repository.findByMediaFileId(file.getId()))
        .hasValueSatisfying(
            outcome -> {
              assertThat(outcome.getSnapshot()).isEqualTo(snapshot);
              assertThat(outcome.getProbeVersion()).isEqualTo(1);
            });
  }

  private MediaFile createMediaFile() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    return mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("probe.mkv")
            .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
            .size(1234)
            .build());
  }

  @Test
  @Transactional
  @DisplayName("Should retain container properties when reading a successful outcome")
  void shouldRetainContainerPropertiesWhenReadingSuccessfulOutcome() {
    var file = createMediaFile();
    var container =
        ProbeContainer.builder()
            .format(Optional.of("matroska,webm"))
            .duration(Optional.of(Duration.ofSeconds(123, 999999999)))
            .bitrate(OptionalLong.of(1234567))
            .build();
    entityManager.persist(outcomeBuilder(file.getId()).container(container).build());
    flushAndClear();

    assertThat(repository.findByMediaFileId(file.getId()))
        .hasValueSatisfying(outcome -> assertThat(outcome.getContainer()).isEqualTo(container));
  }

  @Test
  @Transactional
  @DisplayName("Should retain a terminal probe error independently of matching status")
  void shouldRetainTerminalProbeErrorIndependentlyOfMatchingStatus() {
    var file = createMediaFile();
    entityManager.persist(
        outcomeBuilder(file.getId()).probeError(ProbeError.INVALID_MEDIA).build());
    flushAndClear();

    assertThat(repository.findByMediaFileId(file.getId()))
        .hasValueSatisfying(
            outcome -> {
              assertThat(outcome.getProbeError()).contains(ProbeError.INVALID_MEDIA);
              assertThat(outcome.getContainer()).isEqualTo(ProbeContainer.builder().build());
            });
    assertThat(mediaFileRepository.findById(file.getId()))
        .hasValueSatisfying(
            mediaFile -> assertThat(mediaFile.getStatus()).isEqualTo(MediaFileStatus.MATCHED));
  }

  @Test
  @Transactional
  @DisplayName("Should reject container properties when a terminal error is recorded")
  void shouldRejectContainerPropertiesWhenTerminalErrorIsRecorded() {
    var file = createMediaFile();
    entityManager.persist(
        outcomeBuilder(file.getId())
            .probeError(ProbeError.INVALID_MEDIA)
            .container(ProbeContainer.builder().format(Optional.of("matroska")).build())
            .build());

    assertThatThrownBy(entityManager::flush).isInstanceOf(PersistenceException.class);
  }

  @Test
  @Transactional
  @DisplayName("Should retain every stream and derive playback from the first video and audio")
  void shouldRetainEveryStreamAndDerivePlaybackFromFirstVideoAndAudio() {
    var file = createMediaFile();
    var container = ProbeContainer.builder().duration(Optional.of(Duration.ofSeconds(120))).build();
    var video =
        StreamInfo.builder()
            .index(0)
            .codecType("video")
            .codec(Optional.of("h264"))
            .width(OptionalInt.of(1920))
            .height(OptionalInt.of(1080))
            .framerate(OptionalDouble.of(23.976))
            .bitrate(OptionalLong.of(8000000))
            .build();
    var audio =
        StreamInfo.builder()
            .index(1)
            .codecType("audio")
            .codec(Optional.of("aac"))
            .channels(OptionalInt.of(6))
            .bitrate(OptionalLong.of(640000))
            .language(Optional.of("eng"))
            .build();
    var otherVideo =
        StreamInfo.builder()
            .index(2)
            .codecType("video")
            .codec(Optional.of("hevc"))
            .width(OptionalInt.of(1280))
            .height(OptionalInt.of(720))
            .isDefault(true)
            .build();
    var otherAudio =
        StreamInfo.builder()
            .index(3)
            .codecType("audio")
            .codec(Optional.of("eac3"))
            .channels(OptionalInt.of(2))
            .language(Optional.of("jpn"))
            .isDefault(true)
            .build();
    var subtitle =
        StreamInfo.builder()
            .index(4)
            .codecType("subtitle")
            .codec(Optional.of("subrip"))
            .isForced(true)
            .build();
    var streams = List.of(video, audio, otherVideo, otherAudio, subtitle);
    entityManager.persist(outcomeBuilder(file.getId()).container(container).build());
    streams
        .reversed()
        .forEach(
            stream ->
                entityManager.persist(
                    MediaFileStreamInfo.builder().stream(file.getId(), stream).build()));
    flushAndClear();

    var outcome = repository.findByMediaFileId(file.getId()).orElseThrow().getOutcome();
    assertThat(outcome).isEqualTo(new ProbeOutcome.Success(container, streams));
    var summary = ((ProbeOutcome.Success) outcome).mediaProbe();
    assertThat(summary.videoCodec()).isEqualTo("h264");
    assertThat(summary.audioCodec()).isEqualTo("aac");
    assertThat(summary.width()).isEqualTo(1920);
    assertThat(summary.height()).isEqualTo(1080);
  }

  @Test
  @Transactional
  @DisplayName("Should expose a stored terminal failure through the typed probe reader")
  void shouldExposeStoredTerminalFailureThroughTypedProbeReader() {
    var file = createMediaFile();
    var snapshot = new SourceFileSnapshot(1234, Instant.EPOCH);
    entityManager.persist(
        outcomeBuilder(file.getId())
            .snapshot(snapshot)
            .probeError(ProbeError.NO_VIDEO_STREAM)
            .build());
    flushAndClear();

    assertThat(reader.find(file.getId()))
        .contains(
            new PersistedProbeOutcome(
                snapshot, 1, new ProbeOutcome.Failure(ProbeError.NO_VIDEO_STREAM)));
  }

  @Test
  @Transactional
  @DisplayName("Should leave a future probe version unread when its interpretation is unknown")
  void shouldLeaveFutureProbeVersionUnreadWhenItsInterpretationIsUnknown() {
    var file = createMediaFile();
    entityManager.persist(
        outcomeBuilder(file.getId())
            .probeVersion(2)
            .probeError(ProbeError.NO_VIDEO_STREAM)
            .build());
    flushAndClear();

    assertThat(reader.find(file.getId())).isEmpty();
    assertThat(repository.findByMediaFileId(file.getId())).isPresent();
  }

  @ParameterizedTest
  @CsvSource({"-1, 0, 1", "1234, -1, 1", "1234, 1000000000, 1", "1234, 0, 0"})
  @Transactional
  @DisplayName("Should reject invalid required snapshot and version values")
  void shouldRejectInvalidRequiredSnapshotAndVersionValues(long size, int nanos, int version) {
    var file = createMediaFile();
    entityManager.persist(
        outcomeBuilder(file.getId())
            .sourceSize(size)
            .sourceModifiedEpochSecond(0)
            .sourceModifiedNanos(nanos)
            .probeVersion(version)
            .build());

    assertThatThrownBy(entityManager::flush).isInstanceOf(PersistenceException.class);
  }

  @Test
  @Transactional
  @DisplayName("Should reject an outcome when its media file does not exist")
  void shouldRejectOutcomeWhenMediaFileDoesNotExist() {
    entityManager.persist(outcomeBuilder(UUID.randomUUID()).build());

    assertThatThrownBy(entityManager::flush).isInstanceOf(PersistenceException.class);
  }

  @Test
  @Transactional
  @DisplayName(
      "Should remove streams and make playback data unavailable when a migration invalidates an outcome")
  void shouldRemoveStreamsAndMakePlaybackDataUnavailableWhenMigrationInvalidatesOutcome() {
    var file = createMediaFile();
    var stream =
        StreamInfo.builder().index(0).codecType("video").codec(Optional.of("h264")).build();
    entityManager.persist(outcomeBuilder(file.getId()).build());
    entityManager.persist(MediaFileStreamInfo.builder().stream(file.getId(), stream).build());
    flushAndClear();

    entityManager
        .createNativeQuery("DELETE FROM media_file_container_info WHERE media_file_id = :id")
        .setParameter("id", file.getId())
        .executeUpdate();
    entityManager.clear();

    assertThat(reader.find(file.getId())).isEmpty();
    assertThat(
            entityManager.find(MediaFileStreamInfo.class, new MediaFileStreamId(file.getId(), 0)))
        .isNull();
  }

  private MediaFileContainerInfoBuilder outcomeBuilder(UUID mediaFileId) {
    return MediaFileContainerInfo.builder()
        .mediaFileId(mediaFileId)
        .snapshot(new SourceFileSnapshot(1234, Instant.EPOCH))
        .probeVersion(1);
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }
}
