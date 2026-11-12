package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules by which every {@link MediaFileContainerInfoRepository} keeps the keyframe positions
 * that a packet scan read when the unchanged file is probed again, so the fake that unit tests use
 * behaves as the PostgreSQL repository does.
 */
public interface KeptKeyframePositionsRepositoryContract {

  SourceFileSnapshot SCANNED_SNAPSHOT =
      new SourceFileSnapshot(1234, Instant.parse("2026-11-11T10:00:00Z"));
  SourceFileSnapshot OTHER_SNAPSHOT =
      new SourceFileSnapshot(5678, Instant.parse("2026-11-12T10:00:00Z"));
  int EARLIER_VERSION = ProbeVersion.CURRENT - 1;

  MediaFileContainerInfoRepository repository();

  /** Creates a media file the repository can hold probe results for. */
  UUID createMediaFile();

  @Test
  @DisplayName(
      "Should require a packet scan with the scanned positions kept when a later probe version"
          + " publishes for the same snapshot")
  default void
      shouldRequirePacketScanWithScannedPositionsKeptWhenLaterVersionPublishesSameSnapshot() {
    var mediaFileId = createMediaFile();
    storeScannedOutcome(mediaFileId, EARLIER_VERSION);

    var published =
        repository()
            .publish(publicationBuilder(mediaFileId).probeVersion(ProbeVersion.CURRENT).build());

    assertThat(published).isTrue();
    assertThat(repository().findKeyframeKnowledge(mediaFileId)).contains(keptScanned());
  }

  @Test
  @DisplayName(
      "Should drop the scanned positions when the later probe version publishes for another"
          + " snapshot")
  default void shouldDropScannedPositionsWhenLaterVersionPublishesForAnotherSnapshot() {
    var mediaFileId = createMediaFile();
    storeScannedOutcome(mediaFileId, EARLIER_VERSION);

    repository()
        .publish(
            publicationBuilder(mediaFileId)
                .snapshot(OTHER_SNAPSHOT)
                .probeVersion(ProbeVersion.CURRENT)
                .build());

    assertThat(repository().findKeyframeKnowledge(mediaFileId)).contains(packetScanRequired());
  }

  @Test
  @DisplayName(
      "Should drop the scanned positions when the stored outcome predates the current keyframe"
          + " timeline")
  default void shouldDropScannedPositionsWhenStoredOutcomePredatesCurrentKeyframeTimeline() {
    var mediaFileId = createMediaFile();
    storeScannedOutcome(mediaFileId, ProbeVersion.FIRST_ON_CURRENT_KEYFRAME_TIMELINE - 1);

    repository()
        .publish(
            publicationBuilder(mediaFileId)
                .probeVersion(ProbeVersion.FIRST_ON_CURRENT_KEYFRAME_TIMELINE)
                .build());

    assertThat(repository().findKeyframeKnowledge(mediaFileId)).contains(packetScanRequired());
  }

  @Test
  @DisplayName("Should drop the scanned positions when the later probe chooses another stream")
  default void shouldDropScannedPositionsWhenLaterProbeChoosesAnotherStream() {
    var mediaFileId = createMediaFile();
    storeScannedOutcome(mediaFileId, EARLIER_VERSION);
    var otherStream =
        ProbeOutcome.Success.builder()
            .container(ProbeContainer.builder().build())
            .streams(
                List.of(
                    StreamInfo.builder().index(0).codecType("video").build(),
                    StreamInfo.builder().index(1).codecType("video").build()))
            .keyframes(packetScanRequired())
            .chosenVideoStream(chosenStream(1))
            .build();

    repository()
        .publish(
            publicationBuilder(mediaFileId)
                .probeVersion(ProbeVersion.CURRENT)
                .outcome(otherStream)
                .build());

    assertThat(repository().findKeyframeKnowledge(mediaFileId)).contains(packetScanRequired());
  }

  @Test
  @DisplayName(
      "Should drop the scanned positions when the later probe reports that the keyframes are"
          + " unavailable")
  default void shouldDropScannedPositionsWhenLaterProbeReportsUnavailableKeyframes() {
    var mediaFileId = createMediaFile();
    storeScannedOutcome(mediaFileId, EARLIER_VERSION);
    var unavailable =
        new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.KEYFRAME_WITHOUT_TIMESTAMP);

    repository()
        .publish(
            publicationBuilder(mediaFileId)
                .probeVersion(ProbeVersion.CURRENT)
                .outcome(outcome(unavailable))
                .build());

    assertThat(repository().findKeyframeKnowledge(mediaFileId)).contains(unavailable);
  }

  @Test
  @DisplayName(
      "Should drop the positions that a container index listed when the later probe requires a"
          + " packet scan")
  default void shouldDropPositionsThatContainerIndexListedWhenLaterProbeRequiresPacketScan() {
    var mediaFileId = createMediaFile();
    var indexed =
        new KeyframeKnowledge.Known(
            positionsBuilder().source(KeyframePositionSource.CONTAINER_INDEX).build());
    repository()
        .publish(
            publicationBuilder(mediaFileId)
                .probeVersion(EARLIER_VERSION)
                .outcome(outcome(indexed))
                .build());

    repository()
        .publish(publicationBuilder(mediaFileId).probeVersion(ProbeVersion.CURRENT).build());

    assertThat(repository().findKeyframeKnowledge(mediaFileId)).contains(packetScanRequired());
  }

  @Test
  @DisplayName("Should replace the kept positions when the owed packet scan publishes")
  default void shouldReplaceKeptPositionsWhenOwedPacketScanPublishes() {
    var mediaFileId = createMediaFile();
    storeOutcomeKeepingScannedPositions(mediaFileId);
    var scannedAgain =
        new KeyframeKnowledge.Known(
            positionsBuilder()
                .keyframes(
                    List.of(new KeyframePosition(0, 0), new KeyframePosition(270_000, 270_000)))
                .build());

    var published =
        repository()
            .publishKeyframePacketScan(
                packetScanBuilder(mediaFileId)
                    .probeVersion(ProbeVersion.CURRENT)
                    .keyframes(scannedAgain)
                    .build());

    assertThat(published).isTrue();
    assertThat(repository().findKeyframeKnowledge(mediaFileId)).contains(scannedAgain);
  }

  @Test
  @DisplayName(
      "Should replace the kept positions with unavailable keyframes when the owed packet scan"
          + " reads no keyframes in the stream")
  default void shouldReplaceKeptPositionsWithUnavailableKeyframesWhenOwedScanReadsNoKeyframes() {
    var mediaFileId = createMediaFile();
    storeOutcomeKeepingScannedPositions(mediaFileId);
    var unavailable = new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.NO_KEYFRAMES);

    var published =
        repository()
            .publishKeyframePacketScan(
                packetScanBuilder(mediaFileId)
                    .probeVersion(ProbeVersion.CURRENT)
                    .keyframes(unavailable)
                    .build());

    assertThat(published).isTrue();
    assertThat(repository().findKeyframeKnowledge(mediaFileId)).contains(unavailable);
  }

  @Test
  @DisplayName(
      "Should replace the kept positions with the media error when the owed packet scan ends in"
          + " one")
  default void shouldReplaceKeptPositionsWithMediaErrorWhenOwedPacketScanEndsInOne() {
    var mediaFileId = createMediaFile();
    storeOutcomeKeepingScannedPositions(mediaFileId);
    var mediaError =
        new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.PACKET_SCAN_MEDIA_ERROR);

    var published =
        repository()
            .publishKeyframePacketScan(
                packetScanBuilder(mediaFileId)
                    .probeVersion(ProbeVersion.CURRENT)
                    .keyframes(mediaError)
                    .build());

    assertThat(published).isTrue();
    assertThat(repository().findKeyframeKnowledge(mediaFileId)).contains(mediaError);
  }

  @Test
  @DisplayName(
      "Should store the kept positions as known keyframes when every owed packet scan failed")
  default void shouldStoreKeptPositionsAsKnownKeyframesWhenEveryOwedPacketScanFailed() {
    var mediaFileId = createMediaFile();
    storeOutcomeKeepingScannedPositions(mediaFileId);

    var published =
        repository()
            .publishKeyframePacketScanFailure(
                mediaFileId, new ProbeInputs(SCANNED_SNAPSHOT, ProbeVersion.CURRENT));

    assertThat(published).isTrue();
    assertThat(repository().findKeyframeKnowledge(mediaFileId)).contains(scanned());
  }

  @Test
  @DisplayName(
      "Should store unavailable keyframes when every packet scan failed and no positions were kept")
  default void shouldStoreUnavailableKeyframesWhenEveryPacketScanFailedAndNoPositionsWereKept() {
    var mediaFileId = createMediaFile();
    repository()
        .publish(publicationBuilder(mediaFileId).probeVersion(ProbeVersion.CURRENT).build());

    var published =
        repository()
            .publishKeyframePacketScanFailure(
                mediaFileId, new ProbeInputs(SCANNED_SNAPSHOT, ProbeVersion.CURRENT));

    assertThat(published).isTrue();
    assertThat(repository().findKeyframeKnowledge(mediaFileId))
        .contains(new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.PACKET_SCAN_FAILED));
  }

  @Test
  @DisplayName(
      "Should leave the outcome alone when the failed packet scans read another snapshot or"
          + " version")
  default void shouldLeaveOutcomeAloneWhenFailedPacketScansReadAnotherSnapshotOrVersion() {
    var mediaFileId = createMediaFile();
    storeOutcomeKeepingScannedPositions(mediaFileId);

    var otherSnapshot =
        repository()
            .publishKeyframePacketScanFailure(
                mediaFileId, new ProbeInputs(OTHER_SNAPSHOT, ProbeVersion.CURRENT));
    var otherVersion =
        repository()
            .publishKeyframePacketScanFailure(
                mediaFileId, new ProbeInputs(SCANNED_SNAPSHOT, EARLIER_VERSION));

    assertThat(otherSnapshot).isFalse();
    assertThat(otherVersion).isFalse();
    assertThat(repository().findKeyframeKnowledge(mediaFileId)).contains(keptScanned());
  }

  // An outcome of the given probe version whose packet scan has stored its positions.
  private void storeScannedOutcome(UUID mediaFileId, int probeVersion) {
    assertThat(
            repository()
                .publish(publicationBuilder(mediaFileId).probeVersion(probeVersion).build()))
        .isTrue();
    assertThat(
            repository()
                .publishKeyframePacketScan(
                    packetScanBuilder(mediaFileId).probeVersion(probeVersion).build()))
        .isTrue();
  }

  // An outcome of the current probe version that keeps the earlier version's scanned positions.
  private void storeOutcomeKeepingScannedPositions(UUID mediaFileId) {
    storeScannedOutcome(mediaFileId, EARLIER_VERSION);
    assertThat(
            repository()
                .publish(
                    publicationBuilder(mediaFileId).probeVersion(ProbeVersion.CURRENT).build()))
        .isTrue();
  }

  // A probe of SCANNED_SNAPSHOT that chooses stream 0 and requires a packet scan.
  private static ProbePublication.ProbePublicationBuilder publicationBuilder(UUID mediaFileId) {
    return ProbePublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(SCANNED_SNAPSHOT)
        .outcome(outcome(packetScanRequired()));
  }

  // A packet scan of SCANNED_SNAPSHOT that read the scanned positions.
  private static KeyframePacketScanPublication.KeyframePacketScanPublicationBuilder
      packetScanBuilder(UUID mediaFileId) {
    return KeyframePacketScanPublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(SCANNED_SNAPSHOT)
        .keyframes(scanned());
  }

  // An outcome that names stream 0 as its chosen video stream, as every outcome from probe
  // version 4 does.
  private static ProbeOutcome.Success outcome(KeyframeKnowledge keyframes) {
    return ProbeOutcome.Success.builder()
        .container(ProbeContainer.builder().build())
        .streams(List.of(StreamInfo.builder().index(0).codecType("video").build()))
        .keyframes(keyframes)
        .chosenVideoStream(chosenStream(0))
        .build();
  }

  private static Optional<ChosenVideoStream> chosenStream(int streamIndex) {
    return Optional.of(
        new ChosenVideoStream(
            streamIndex, VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1))));
  }

  private static KeyframeKnowledge packetScanRequired() {
    return new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX);
  }

  private static KeyframeKnowledge scanned() {
    return new KeyframeKnowledge.Known(positionsBuilder().build());
  }

  // The scanned positions, kept beside the packet scan that the later probe requires.
  private static KeyframeKnowledge keptScanned() {
    return new KeyframeKnowledge.PacketScanRequiredWithKeptPositions(
        KeyframePacketScanReason.NO_CONTAINER_INDEX, positionsBuilder().build());
  }

  // Positions of stream 0 that a packet scan read in a 90 kHz time base.
  private static KeyframePositions.KeyframePositionsBuilder positionsBuilder() {
    return KeyframePositions.builder()
        .streamIndex(0)
        .timeBaseNumerator(1)
        .timeBaseDenominator(90_000)
        .source(KeyframePositionSource.PACKET_SCAN)
        .keyframes(List.of(new KeyframePosition(0, 0), new KeyframePosition(90_000, 90_000)));
  }
}
