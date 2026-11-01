package com.streamarr.server.repositories;

import static com.streamarr.server.fixtures.StreamMeasurementFixture.measurement;
import static com.streamarr.server.fixtures.StreamMeasurementFixture.positions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.BitrateScanChunkPublication;
import com.streamarr.server.domain.task.BitrateScanInputs;
import com.streamarr.server.domain.task.BitrateScanKey;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.StreamMeasurementPublication;
import com.streamarr.server.fixtures.BitrateScanChunkFixture;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Bitrate Scan Repository Integration Tests")
class JooqBitrateScanRepositoryIT extends AbstractIntegrationTest
    implements BitrateScanRepositoryContract {

  private static final SourceFileSnapshot SNAPSHOT =
      new SourceFileSnapshot(1_234, Instant.parse("2026-10-30T10:00:00Z"));
  private static final String MATROSKA = "matroska,webm";
  private static final String MP4 = "mov,mp4,m4a,3gp,3g2,mj2";

  @Autowired private BitrateScanRepository repository;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private LibraryRepository libraries;
  @Autowired private PlatformTransactionManager transactionManager;

  private final List<MediaFile> createdFiles = new ArrayList<>();
  private final List<Library> createdLibraries = new ArrayList<>();

  @AfterEach
  void removeCreatedFiles() {
    mediaFiles.deleteAll(createdFiles);
    libraries.deleteAll(createdLibraries);
  }

  @Override
  public BitrateScanRepository repository() {
    return repository;
  }

  @Override
  public StoredScan createFileNeedingScan() {
    var mediaFileId = createMediaFile();
    outcomes.publish(publication(mediaFileId, 1, outcome().build()));
    return new StoredScan(mediaFileId, currentKey(mediaFileId));
  }

  @Override
  public BitrateScanKey storeOtherKeyframePositions(UUID mediaFileId) {
    outcomes.publish(
        publication(mediaFileId, 1, outcome().keyframes(known(positions(45_000))).build()));
    return currentKey(mediaFileId);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scannedOutcomes")
  @DisplayName("Should need a scan when the stored outcome has a copyable video and its positions")
  void shouldNeedAScanWhenTheStoredOutcomeHasACopyableVideoAndItsPositions(
      String description, ProbeOutcome.Success outcome) {
    var mediaFileId = createMediaFile();

    outcomes.publish(publication(mediaFileId, 1, outcome));

    assertThat(repository.findBitrateScanInputs(mediaFileId)).isPresent();
  }

  private static Stream<Arguments> scannedOutcomes() {
    return Stream.of(
        arguments("H.264 in Matroska", outcome().build()),
        arguments("HEVC", outcome().streams(List.of(video("hevc").build())).build()),
        arguments("AV1", outcome().streams(List.of(video("av1").build())).build()),
        arguments(
            "MP4 with positions from a packet scan",
            outcome()
                .container(container(MP4))
                .keyframes(known(positions(90_000, KeyframePositionSource.PACKET_SCAN)))
                .build()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unscannedOutcomes")
  @DisplayName("Should need no scan when the stored outcome gives a copy nothing to measure")
  void shouldNeedNoScanWhenTheStoredOutcomeGivesACopyNothingToMeasure(
      String description, ProbeOutcome outcome) {
    var mediaFileId = createMediaFile();

    outcomes.publish(publication(mediaFileId, 1, outcome));

    assertThat(repository.findBitrateScanInputs(mediaFileId)).isEmpty();
  }

  private static Stream<Arguments> unscannedOutcomes() {
    return Stream.of(
        arguments("MPEG-2 video", outcome().streams(List.of(video("mpeg2video").build())).build()),
        arguments(
            "MP4 with positions from its sample tables",
            outcome().container(container(MP4)).build()),
        arguments(
            "positions that need a packet scan",
            outcome()
                .keyframes(
                    new KeyframeKnowledge.PacketScanRequired(
                        KeyframePacketScanReason.NO_CONTAINER_INDEX))
                .build()),
        arguments("a failed probe", new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA)));
  }

  @Test
  @DisplayName(
      "Should read the positions, the duration, the audio streams and the probed bitrate when the"
          + " file needs a scan")
  void shouldReadThePositionsDurationAudioStreamsAndProbedBitrateWhenTheFileNeedsAScan() {
    var mediaFileId = createMediaFile();
    outcomes.publish(
        publication(
            mediaFileId,
            1,
            outcome()
                .streams(
                    List.of(
                        video("h264").bitrate(OptionalLong.of(8_000_000)).build(),
                        audio(1),
                        StreamInfo.builder().index(2).codecType("subtitle").build(),
                        audio(3)))
                .build()));

    assertThat(repository.findBitrateScanInputs(mediaFileId))
        .hasValueSatisfying(
            inputs -> {
              assertThat(inputs.key())
                  .isEqualTo(
                      new BitrateScanKey(
                          SNAPSHOT,
                          inputs.key().keyframePositionsDigest(),
                          StreamMeasurements.CURRENT_VERSION));
              assertThat(inputs.positions()).isEqualTo(positions(90_000));
              assertThat(inputs.containerDuration()).contains(Duration.ofMillis(2_500));
              assertThat(inputs.audioStreamIndexes()).containsExactly(1, 3);
              assertThat(inputs.chosenVideoHasProbedBitrate()).isTrue();
            });
  }

  @Test
  @DisplayName(
      "Should keep the stored chunks when a probe stores the same keyframe positions again")
  void shouldKeepTheStoredChunksWhenAProbeStoresTheSameKeyframePositionsAgain() {
    var scan = createFileNeedingScan();
    repository.storeChunk(chunkPublication(scan));

    outcomes.publish(publication(scan.mediaFileId(), 2, outcome().build()));

    assertThat(currentKey(scan.mediaFileId())).isEqualTo(scan.key());
    assertThat(repository.findChunks(scan.mediaFileId(), scan.key())).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should delete the stored chunks when a probe stores other keyframe positions, even once the"
          + " earlier positions return")
  void shouldDeleteTheStoredChunksWhenAProbeStoresOtherKeyframePositionsEvenOnceTheyReturn() {
    var scan = createFileNeedingScan();
    repository.storeChunk(chunkPublication(scan));
    storeOtherKeyframePositions(scan.mediaFileId());

    storeKeyframePositionsAgain(scan.mediaFileId());

    assertThat(repository.findChunks(scan.mediaFileId(), scan.key())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the current measurement when a scan of earlier inputs publishes its measurement")
  void shouldKeepTheCurrentMeasurementWhenAScanOfEarlierInputsPublishesItsMeasurement() {
    var earlier = createFileNeedingScan();
    var current =
        new StoredScan(earlier.mediaFileId(), storeOtherKeyframePositions(earlier.mediaFileId()));
    var measurement = measurementOf(current);
    repository.publishMeasurement(measurement);

    assertThat(repository.publishMeasurement(measurementOf(earlier))).isFalse();

    assertThat(outcomes.findStreamMeasurements(earlier.mediaFileId()))
        .contains(measurement.measurements());
  }

  @Test
  @DisplayName("Should require the caller's transaction when the scan inputs are locked")
  void shouldRequireTheCallersTransactionWhenTheScanInputsAreLocked() {
    var scan = createFileNeedingScan();
    var mediaFileId = scan.mediaFileId();

    assertThatExceptionOfType(IllegalTransactionStateException.class)
        .isThrownBy(() -> repository.lockBitrateScanInputs(mediaFileId));
    assertThat(lockedInputs(scan.mediaFileId()))
        .hasValueSatisfying(inputs -> assertThat(inputs.key()).isEqualTo(scan.key()));
    assertThat(lockedInputs(UUID.randomUUID())).isEmpty();
  }

  private Optional<BitrateScanInputs> lockedInputs(UUID mediaFileId) {
    return new TransactionTemplate(transactionManager)
        .execute(_ -> repository.lockBitrateScanInputs(mediaFileId));
  }

  private void storeKeyframePositionsAgain(UUID mediaFileId) {
    outcomes.publish(publication(mediaFileId, 1, outcome().build()));
  }

  private BitrateScanKey currentKey(UUID mediaFileId) {
    return repository.findBitrateScanInputs(mediaFileId).orElseThrow().key();
  }

  private static BitrateScanChunkPublication chunkPublication(StoredScan scan) {
    return BitrateScanChunkPublication.builder()
        .mediaFileId(scan.mediaFileId())
        .key(scan.key())
        .chunk(BitrateScanChunkFixture.chunk(0, 2))
        .build();
  }

  private static StreamMeasurementPublication measurementOf(StoredScan scan) {
    return StreamMeasurementPublication.builder()
        .mediaFileId(scan.mediaFileId())
        .key(scan.key())
        .measurements(measurement())
        .build();
  }

  private UUID createMediaFile() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    createdLibraries.add(library);
    var file =
        mediaFiles.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("scan.mkv")
                .filepathUri("file:///library/" + UUID.randomUUID() + ".mkv")
                .size(1_234)
                .build());
    createdFiles.add(file);
    return file.getId();
  }

  private static ProbePublication publication(
      UUID mediaFileId, int probeVersion, ProbeOutcome outcome) {
    return ProbePublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(SNAPSHOT)
        .probeVersion(probeVersion)
        .outcome(outcome)
        .build();
  }

  // An H.264 video stream and an AAC audio stream in Matroska, with two keyframes from the
  // container index.
  private static ProbeOutcome.Success.SuccessBuilder outcome() {
    return ProbeOutcome.Success.builder()
        .container(container(MATROSKA))
        .streams(List.of(video("h264").build(), audio(1)))
        .keyframes(known(positions(90_000)))
        .streamMeasurements(Optional.empty());
  }

  private static ProbeContainer container(String format) {
    return ProbeContainer.builder()
        .format(Optional.of(format))
        .duration(Optional.of(Duration.ofMillis(2_500)))
        .build();
  }

  private static StreamInfo.StreamInfoBuilder video(String codec) {
    return StreamInfo.builder().index(0).codecType("video").codec(Optional.of(codec));
  }

  private static StreamInfo audio(int index) {
    return StreamInfo.builder().index(index).codecType("audio").codec(Optional.of("aac")).build();
  }

  private static KeyframeKnowledge known(KeyframePositions positions) {
    return new KeyframeKnowledge.Known(positions);
  }
}
