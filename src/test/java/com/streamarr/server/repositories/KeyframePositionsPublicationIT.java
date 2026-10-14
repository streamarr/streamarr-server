package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.Tables.MEDIA_FILE_KEYFRAME_POSITIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.exceptions.MalformedKeyframePositionsException;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.probe.PersistedProbeReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("IntegrationTest")
@DisplayName("Keyframe positions publication")
class KeyframePositionsPublicationIT extends AbstractIntegrationTest {

  private static final SourceFileSnapshot SNAPSHOT_A =
      new SourceFileSnapshot(1234, Instant.parse("2026-10-14T10:00:00Z"));
  private static final SourceFileSnapshot SNAPSHOT_B =
      new SourceFileSnapshot(5678, Instant.parse("2026-10-15T10:00:00Z"));
  // A 90 kHz time base, as MPEG-TS sources use.
  private static final long TICKS_PER_SECOND = 90_000;
  // Two frames at 24 fps: the presentation delay that B-frame reordering gives a keyframe.
  private static final long B_FRAME_PRESENTATION_DELAY = 7_500;

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
  @DisplayName("Should round-trip stored positions when an outcome is published")
  void shouldRoundTripStoredPositionsWhenOutcomeIsPublished() {
    var file = createMediaFile();
    var outcome = success(new KeyframeKnowledge.Known(twoHourSourcePositions()));

    var published = repository.publish(publication(file.getId(), SNAPSHOT_A, outcome));

    assertThat(published).isTrue();
    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(outcome));
  }

  @Test
  @DisplayName("Should store PacketScanRequired without positions")
  void shouldStorePacketScanRequiredWithoutPositions() {
    var file = createMediaFile();
    var outcome =
        success(
            new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX));

    repository.publish(publication(file.getId(), SNAPSHOT_A, outcome));

    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(outcome));
    assertThat(positionsRowCount(file.getId())).isZero();
  }

  @Test
  @DisplayName("Should store unavailable keyframes without positions")
  void shouldStoreUnavailableKeyframesWithoutPositions() {
    var file = createMediaFile();
    var outcome =
        success(
            new KeyframeKnowledge.Unavailable(
                KeyframesUnavailableReason.KEYFRAME_WITHOUT_TIMESTAMP));

    repository.publish(publication(file.getId(), SNAPSHOT_A, outcome));

    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(outcome));
    assertThat(positionsRowCount(file.getId())).isZero();
  }

  @Test
  @DisplayName("Should delete positions when the source snapshot changes")
  void shouldDeletePositionsWhenSourceSnapshotChanges() {
    var file = createMediaFile();
    repository.publish(
        publication(
            file.getId(),
            SNAPSHOT_A,
            success(new KeyframeKnowledge.Known(twoHourSourcePositions()))));

    repository.trySaveProbeRequest(file.getId(), new ProbeInputs(SNAPSHOT_B, 1));

    assertThat(positionsRowCount(file.getId())).isZero();
    assertThat(reader.find(file.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should delete positions when a republished outcome requires a packet scan")
  void shouldDeletePositionsWhenRepublishedOutcomeRequiresPacketScan() {
    var file = createMediaFile();
    repository.publish(
        publication(
            file.getId(),
            SNAPSHOT_A,
            success(new KeyframeKnowledge.Known(twoHourSourcePositions()))));
    var replacement =
        success(
            new KeyframeKnowledge.PacketScanRequired(
                KeyframePacketScanReason.UNREADABLE_LAST_INDEXED_KEYFRAME));

    repository.publish(publication(file.getId(), SNAPSHOT_A, replacement));

    assertThat(positionsRowCount(file.getId())).isZero();
    assertThat(reader.find(file.getId()))
        .hasValueSatisfying(stored -> assertThat(stored.outcome()).isEqualTo(replacement));
  }

  @Test
  @DisplayName("Should refuse a positions row when the outcome's keyframes are not known")
  void shouldRefusePositionsRowWhenOutcomesKeyframesAreNotKnown() {
    var file = createMediaFile();
    repository.publish(
        publication(
            file.getId(),
            SNAPSHOT_A,
            success(
                new KeyframeKnowledge.PacketScanRequired(
                    KeyframePacketScanReason.NO_CONTAINER_INDEX))));
    var insert =
        dsl.insertInto(MEDIA_FILE_KEYFRAME_POSITIONS)
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID, file.getId())
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.STREAM_INDEX, 0)
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.TIME_BASE_NUMERATOR, 1)
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.TIME_BASE_DENOMINATOR, 90_000)
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.KEYFRAME_COUNT, 1)
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.ENCODED_TIMES, new byte[] {1, 0, 0})
            .set(MEDIA_FILE_KEYFRAME_POSITIONS.SOURCE, KeyframePositionSource.PACKET_SCAN.name());

    assertThatThrownBy(insert::execute).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName(
      "Should refuse stored positions when their keyframe count differs from the encoded keyframes")
  void shouldRefuseStoredPositionsWhenTheirKeyframeCountDiffersFromEncodedKeyframes() {
    var file = createMediaFile();
    repository.publish(
        publication(
            file.getId(),
            SNAPSHOT_A,
            success(new KeyframeKnowledge.Known(twoHourSourcePositions()))));
    dsl.update(MEDIA_FILE_KEYFRAME_POSITIONS)
        .set(MEDIA_FILE_KEYFRAME_POSITIONS.KEYFRAME_COUNT, 3_599)
        .where(MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID.eq(file.getId()))
        .execute();
    var mediaFileId = file.getId();

    assertThatThrownBy(() -> reader.find(mediaFileId))
        .isInstanceOf(MalformedKeyframePositionsException.class);
  }

  private static KeyframePositions twoHourSourcePositions() {
    return KeyframePositions.builder()
        .streamIndex(0)
        .timeBaseNumerator(1)
        .timeBaseDenominator((int) TICKS_PER_SECOND)
        .source(KeyframePositionSource.CONTAINER_INDEX)
        .keyframes(
            LongStream.range(0, 3_600)
                .mapToObj(
                    keyframe -> {
                      var decodeTime = keyframe * 2 * TICKS_PER_SECOND - 3_750;
                      return new KeyframePosition(
                          decodeTime + B_FRAME_PRESENTATION_DELAY, decodeTime);
                    })
                .toList())
        .build();
  }

  private MediaFile createMediaFile() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var file =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("keyframes.mkv")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
                .size(1234)
                .build());
    createdFiles.add(file);
    return file;
  }

  private int positionsRowCount(UUID mediaFileId) {
    return dsl.fetchCount(
        MEDIA_FILE_KEYFRAME_POSITIONS, MEDIA_FILE_KEYFRAME_POSITIONS.MEDIA_FILE_ID.eq(mediaFileId));
  }

  private static ProbePublication publication(
      UUID mediaFileId, SourceFileSnapshot snapshot, ProbeOutcome outcome) {
    return ProbePublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(snapshot)
        .probeVersion(1)
        .outcome(outcome)
        .build();
  }

  private static ProbeOutcome.Success success(KeyframeKnowledge keyframes) {
    return new ProbeOutcome.Success(
        ProbeContainer.builder().build(),
        List.of(StreamInfo.builder().index(0).codecType("video").build()),
        keyframes);
  }
}
