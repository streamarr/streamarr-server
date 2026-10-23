package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeTimedOutException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.fakes.CapturingKeyframePacketScanRequests;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.PlaybackProbeClaims;
import com.streamarr.server.services.streaming.KeyframePacketScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Keyframe packet scan execution")
class KeyframePacketScanExecutionTest {

  private static final KeyframePositions SCANNED_POSITIONS =
      KeyframePositions.builder()
          .streamIndex(0)
          .timeBaseNumerator(1)
          .timeBaseDenominator(90_000)
          .source(KeyframePositionSource.PACKET_SCAN)
          .keyframes(List.of(new KeyframePosition(0, 0), new KeyframePosition(90_000, 90_000)))
          .build();

  @TempDir Path tempDir;

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeMediaFileContainerInfoRepository outcomes =
      new FakeMediaFileContainerInfoRepository();
  private final List<ProbeExecutionRequest> scanRequests = new ArrayList<>();
  private KeyframePacketScanner scanner = scanAnswering(success(known()));
  private Path source;
  private MediaFile mediaFile;

  @BeforeEach
  void setUp() throws IOException {
    source = tempDir.resolve("movie.ts");
    Files.write(source, new byte[] {1, 2, 3});
    mediaFile = saveMediaFile(source);
  }

  @Test
  @DisplayName("Should store keyframe positions from the packet scan when the probe asked for one")
  void shouldStoreKeyframePositionsFromThePacketScanWhenTheProbeAskedForOne() {
    var packetScans = new CapturingKeyframePacketScanRequests();
    var producer = new FakeFfprobeService();
    producer.answerWith(success(packetScanRequired()));
    probeExecution(producer, packetScans)
        .execute(probeRequest(mediaFile, source), ProbePriority.BACKGROUND);
    var request = packetScans.requests().getFirst();

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new KeyframePacketScanResult.Completed());
    assertThat(scanRequests)
        .singleElement()
        .satisfies(
            scan -> {
              assertThat(scan.sourcePath()).isEqualTo(source);
              assertThat(scan.probeVersion()).isEqualTo(ProbeVersion.CURRENT);
              assertThat(scan.attemptId()).isNotNull();
            });
    assertThat(storedKeyframes()).isEqualTo(known());
  }

  @Test
  @DisplayName("Should discard a packet-scan result when the source changed during the scan")
  void shouldDiscardAPacketScanResultWhenTheSourceChangedDuringTheScan() {
    var request = storePacketScanRequired();
    scanner =
        scan -> {
          append(source);
          return success(known());
        };

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new KeyframePacketScanResult.Completed());
    assertThat(storedKeyframes()).isEqualTo(packetScanRequired());
  }

  @Test
  @DisplayName("Should complete without scanning when the source changed before the scan")
  void shouldCompleteWithoutScanningWhenTheSourceChangedBeforeTheScan() {
    var request = storePacketScanRequired();
    append(source);

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new KeyframePacketScanResult.Completed());
    assertThat(scanRequests).isEmpty();
  }

  @Test
  @DisplayName("Should complete without scanning when the source file has vanished")
  void shouldCompleteWithoutScanningWhenTheSourceFileHasVanished() throws IOException {
    var request = storePacketScanRequired();
    Files.delete(source);

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new KeyframePacketScanResult.Completed());
    assertThat(scanRequests).isEmpty();
  }

  @Test
  @DisplayName("Should complete without scanning when the stored outcome needs no packet scan")
  void shouldCompleteWithoutScanningWhenTheStoredOutcomeNeedsNoPacketScan() {
    var request = storePacketScanRequired();
    outcomes.publishKeyframePacketScan(
        KeyframePacketScanPublication.builder()
            .mediaFileId(mediaFile.getId())
            .snapshot(request.snapshot())
            .probeVersion(request.probeVersion())
            .keyframes(known())
            .build());

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new KeyframePacketScanResult.Completed());
    assertThat(scanRequests).isEmpty();
  }

  @Test
  @DisplayName("Should complete without scanning when the request names another probe version")
  void shouldCompleteWithoutScanningWhenTheRequestNamesAnotherProbeVersion() {
    var request = storePacketScanRequired(ProbeVersion.CURRENT + 1);

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new KeyframePacketScanResult.Completed());
    assertThat(scanRequests).isEmpty();
  }

  @Test
  @DisplayName("Should defer the packet scan when every compatible worker is busy")
  void shouldDeferThePacketScanWhenEveryCompatibleWorkerIsBusy() {
    var request = storePacketScanRequired();
    scanner =
        _ -> {
          throw new ProbeWorkersBusyException("All compatible workers are busy");
        };

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new KeyframePacketScanResult.Deferred());
    assertThat(storedKeyframes()).isEqualTo(packetScanRequired());
  }

  @Test
  @DisplayName("Should store unavailable keyframes when the packet scan ends in a media error")
  void shouldStoreUnavailableKeyframesWhenThePacketScanEndsInAMediaError() {
    var request = storePacketScanRequired();
    scanner = scanAnswering(new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA));

    execution().execute(request);

    assertThat(storedKeyframes())
        .isEqualTo(
            new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.PACKET_SCAN_MEDIA_ERROR));
  }

  @Test
  @DisplayName("Should store unavailable keyframes when the packet scan finds no keyframes")
  void shouldStoreUnavailableKeyframesWhenThePacketScanFindsNoKeyframes() {
    var request = storePacketScanRequired();
    var unavailable = new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.NO_KEYFRAMES);
    scanner = scanAnswering(success(unavailable));

    execution().execute(request);

    assertThat(storedKeyframes()).isEqualTo(unavailable);
  }

  @ParameterizedTest
  @MethodSource("keyframesThatNoPacketScanReports")
  @DisplayName("Should end the scan as failed when the packet scan reports no keyframe result")
  void shouldEndTheScanAsFailedWhenThePacketScanReportsNoKeyframeResult(
      KeyframeKnowledge keyframes) {
    var request = storePacketScanRequired();
    scanner = scanAnswering(success(keyframes));

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new KeyframePacketScanResult.Failed());
    assertThat(storedKeyframes()).isEqualTo(packetScanRequired());
  }

  @Test
  @DisplayName("Should end the scan as failed when the packet scan times out")
  void shouldEndTheScanAsFailedWhenThePacketScanTimesOut() {
    var request = storePacketScanRequired();
    scanner =
        _ -> {
          throw new ProbeTimedOutException(new TimeoutException());
        };

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new KeyframePacketScanResult.Failed());
    assertThat(storedKeyframes()).isEqualTo(packetScanRequired());
  }

  @Test
  @DisplayName("Should fail for retry when the source's attributes cannot be read")
  void shouldFailForRetryWhenTheSourcesAttributesCannotBeRead() throws IOException {
    var request = storePacketScanRequired();
    Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("---------"));
    var execution = execution();

    try {
      assertThatThrownBy(() -> execution.execute(request))
          .isInstanceOf(ProbeExecutionException.class);
    } finally {
      Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwx------"));
    }

    assertThat(scanRequests).isEmpty();
  }

  private static Stream<KeyframeKnowledge> keyframesThatNoPacketScanReports() {
    return Stream.of(packetScanRequired(), new KeyframeKnowledge.NotReported());
  }

  private KeyframePacketScanExecution execution() {
    return KeyframePacketScanExecution.builder()
        .reader(new PersistedProbeReader(outcomes))
        .scanner(
            request -> {
              scanRequests.add(request);
              return scanner.scanKeyframePackets(request);
            })
        .fileSystem(FileSystems.getDefault())
        .outcomes(outcomes)
        .build();
  }

  private ProbeExecution probeExecution(
      FakeFfprobeService producer, CapturingKeyframePacketScanRequests packetScans) {
    return ProbeExecution.builder()
        .mediaFiles(mediaFiles)
        .reader(new PersistedProbeReader(outcomes))
        .producer(producer)
        .fileSystem(FileSystems.getDefault())
        .outcomes(outcomes)
        .packetScans(packetScans)
        .transactionManager(new FakeTransactionManager())
        .playbackProbeClaims(new PlaybackProbeClaims(System::nanoTime, new SimpleMeterRegistry()))
        .build();
  }

  private KeyframePacketScanTaskRequest storePacketScanRequired() {
    return storePacketScanRequired(ProbeVersion.CURRENT);
  }

  private KeyframePacketScanTaskRequest storePacketScanRequired(int probeVersion) {
    return storePacketScanRequired(mediaFile, source, probeVersion);
  }

  private KeyframePacketScanTaskRequest storePacketScanRequired(
      MediaFile file, Path path, int probeVersion) {
    var snapshot = snapshot(path);
    outcomes.publish(
        ProbePublication.builder()
            .mediaFileId(file.getId())
            .snapshot(snapshot)
            .probeVersion(probeVersion)
            .outcome(success(packetScanRequired()))
            .build());
    return KeyframePacketScanTaskRequest.builder()
        .mediaFileId(file.getId())
        .filepathUri(file.getFilepathUri())
        .snapshot(snapshot)
        .probeVersion(probeVersion)
        .build();
  }

  private KeyframeKnowledge storedKeyframes() {
    return outcomes.findKeyframeKnowledge(mediaFile.getId()).orElseThrow();
  }

  private MediaFile saveMediaFile(Path path) {
    return mediaFiles.save(
        MediaFile.builder()
            .libraryId(UUID.randomUUID())
            .status(MediaFileStatus.MATCHED)
            .filename(path.getFileName().toString())
            .filepathUri(FilepathCodec.encode(path))
            .size(3)
            .build());
  }

  private static ProbeTaskRequest probeRequest(MediaFile file, Path path) {
    return ProbeTaskRequest.builder()
        .mediaFileId(file.getId())
        .libraryId(file.getLibraryId())
        .filepathUri(file.getFilepathUri())
        .snapshot(snapshot(path))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }

  private static KeyframePacketScanner scanAnswering(ProbeOutcome outcome) {
    return _ -> outcome;
  }

  private static KeyframeKnowledge known() {
    return new KeyframeKnowledge.Known(SCANNED_POSITIONS);
  }

  private static KeyframeKnowledge packetScanRequired() {
    return new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX);
  }

  private static ProbeOutcome.Success success(KeyframeKnowledge keyframes) {
    return new ProbeOutcome.Success(
        ProbeContainer.builder().build(),
        List.of(StreamInfo.builder().codecType("video").build()),
        keyframes);
  }

  private static SourceFileSnapshot snapshot(Path path) {
    try {
      var attributes = Files.readAttributes(path, BasicFileAttributes.class);
      return new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant());
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static void append(Path path) {
    try {
      Files.write(path, new byte[] {9}, StandardOpenOption.APPEND);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
