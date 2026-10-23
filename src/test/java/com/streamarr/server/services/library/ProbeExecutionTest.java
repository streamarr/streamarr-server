package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
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
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.fakes.CapturingKeyframePacketScanRequests;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.PlaybackProbeClaims;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Probe execution")
class ProbeExecutionTest {

  @TempDir Path tempDir;

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeMediaFileContainerInfoRepository outcomes =
      new FakeMediaFileContainerInfoRepository();
  private final FakeFfprobeService producer = new FakeFfprobeService();
  private final PlaybackProbeClaims playbackProbeClaims =
      new PlaybackProbeClaims(Clock.systemUTC(), new SimpleMeterRegistry());
  private final CapturingKeyframePacketScanRequests packetScans =
      new CapturingKeyframePacketScanRequests();
  private final FakeTransactionManager transactionManager = new FakeTransactionManager();
  private Path source;
  private MediaFile mediaFile;

  @BeforeEach
  void setUp() throws IOException {
    source = tempDir.resolve("movie.mkv");
    Files.write(source, new byte[] {1, 2, 3});
    mediaFile =
        mediaFiles.save(
            MediaFile.builder()
                .libraryId(UUID.randomUUID())
                .status(MediaFileStatus.MATCHED)
                .filename("movie.mkv")
                .filepathUri(FilepathCodec.encode(source))
                .size(3)
                .build());
  }

  @Test
  @DisplayName("Should publish the outcome and complete when the source is unchanged")
  void shouldPublishTheOutcomeAndCompleteWhenTheSourceIsUnchanged() {
    var request = request(ProbeVersion.CURRENT);

    var result = execution().execute(request, ProbePriority.BACKGROUND);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    assertThat(outcomes.publications())
        .singleElement()
        .satisfies(
            publication -> {
              assertThat(publication.mediaFileId()).isEqualTo(mediaFile.getId());
              assertThat(publication.snapshot()).isEqualTo(request.snapshot());
              assertThat(publication.probeVersion()).isEqualTo(ProbeVersion.CURRENT);
              assertThat(publication.outcome()).isEqualTo(producer.probe(anyRequestFor(source)));
            });
  }

  @Test
  @DisplayName("Should request a packet scan when the published outcome needs one")
  void shouldRequestAPacketScanWhenThePublishedOutcomeNeedsOne() {
    var request = request(ProbeVersion.CURRENT);
    producer.answerWith(
        success(
            new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX)));

    var result = execution().execute(request, ProbePriority.BACKGROUND);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    assertThat(packetScans.requests())
        .containsExactly(
            KeyframePacketScanTaskRequest.builder()
                .mediaFileId(mediaFile.getId())
                .filepathUri(mediaFile.getFilepathUri())
                .snapshot(request.snapshot())
                .probeVersion(ProbeVersion.CURRENT)
                .build());
  }

  @Test
  @DisplayName(
      "Should count the probe as in progress and send it at playback priority when a"
          + " playback probe runs")
  void shouldCountTheProbeAsInProgressAndSendItAtPlaybackPriorityWhenAPlaybackProbeRuns() {
    var inProgressDuringProbe = new AtomicInteger(-1);
    producer.runDuringProbe(() -> inProgressDuringProbe.set(playbackProbeClaims.inProgress()));

    execution().execute(request(ProbeVersion.CURRENT), ProbePriority.PLAYBACK);

    assertThat(inProgressDuringProbe).hasValue(1);
    assertThat(playbackProbeClaims.inProgress()).isZero();
    assertThat(producer.lastRequest().orElseThrow().priority()).isEqualTo(ProbePriority.PLAYBACK);
  }

  @Test
  @DisplayName("Should count no playback probe in progress when a background probe runs")
  void shouldCountNoPlaybackProbeInProgressWhenABackgroundProbeRuns() {
    var inProgressDuringProbe = new AtomicInteger(-1);
    producer.runDuringProbe(() -> inProgressDuringProbe.set(playbackProbeClaims.inProgress()));

    execution().execute(request(ProbeVersion.CURRENT), ProbePriority.BACKGROUND);

    assertThat(inProgressDuringProbe).hasValue(0);
    assertThat(producer.lastRequest().orElseThrow().priority()).isEqualTo(ProbePriority.BACKGROUND);
  }

  @ParameterizedTest
  @MethodSource("outcomesThatNeedNoPacketScan")
  @DisplayName("Should request no packet scan when the published outcome needs none")
  void shouldRequestNoPacketScanWhenThePublishedOutcomeNeedsNone(ProbeOutcome outcome) {
    producer.answerWith(outcome);

    execution().execute(request(ProbeVersion.CURRENT), ProbePriority.BACKGROUND);

    assertThat(outcomes.publications()).singleElement();
    assertThat(packetScans.requests()).isEmpty();
  }

  @Test
  @DisplayName("Should request no packet scan when a newer request refuses the publication")
  void shouldRequestNoPacketScanWhenANewerRequestRefusesThePublication() {
    var request = request(ProbeVersion.CURRENT);
    producer.answerWith(
        success(
            new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX)));
    producer.runDuringProbe(
        () ->
            outcomes.trySaveProbeRequest(
                mediaFile.getId(),
                new ProbeInputs(
                    new SourceFileSnapshot(99, request.snapshot().modifiedAt()),
                    ProbeVersion.CURRENT)));

    execution().execute(request, ProbePriority.BACKGROUND);

    assertThat(outcomes.publications()).isEmpty();
    assertThat(packetScans.requests()).isEmpty();
  }

  private static Stream<ProbeOutcome> outcomesThatNeedNoPacketScan() {
    return Stream.of(
        new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA),
        success(new KeyframeKnowledge.NotReported()),
        success(new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.NO_KEYFRAMES)),
        success(
            new KeyframeKnowledge.Known(
                KeyframePositions.builder()
                    .streamIndex(0)
                    .timeBaseNumerator(1)
                    .timeBaseDenominator(1_000)
                    .source(KeyframePositionSource.CONTAINER_INDEX)
                    .keyframes(List.of(new KeyframePosition(0, 0)))
                    .build())));
  }

  private static ProbeOutcome.Success success(KeyframeKnowledge keyframes) {
    return new ProbeOutcome.Success(
        ProbeContainer.builder().build(),
        List.of(StreamInfo.builder().codecType("video").build()),
        keyframes);
  }

  @Test
  @DisplayName("Should carry the source and requested version with a fresh attempt id when probing")
  void shouldCarryTheSourceAndRequestedVersionWithAFreshAttemptIdWhenProbing() {
    var request = request(ProbeVersion.CURRENT);
    var execution = execution();
    producer.runDuringProbe(
        () -> {
          throw new ProbeExecutionException(ItemFailureReason.TEMPORARY, "worker disconnected");
        });

    assertThatThrownBy(() -> execution.execute(request, ProbePriority.BACKGROUND))
        .isInstanceOf(ProbeExecutionException.class)
        .hasMessage("worker disconnected");
    assertThat(outcomes.findByMediaFileId(mediaFile.getId())).isEmpty();
    var failedAttempt = producer.lastRequest().orElseThrow().attemptId();

    producer.runDuringProbe(() -> {});
    var result = execution.execute(request, ProbePriority.BACKGROUND);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    assertThat(producer.lastRequest())
        .hasValueSatisfying(
            invoked -> {
              assertThat(invoked.sourcePath()).isEqualTo(source);
              assertThat(invoked.probeVersion()).isEqualTo(ProbeVersion.CURRENT);
              assertThat(invoked.attemptId()).isNotNull().isNotEqualTo(failedAttempt);
            });
    assertThat(outcomes.findByMediaFileId(mediaFile.getId()))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.getSnapshot()).isEqualTo(request.snapshot());
              assertThat(stored.getProbeVersion()).isEqualTo(ProbeVersion.CURRENT);
              assertThat(stored.getProbeError()).isEmpty();
              assertThat(
                      stored.getOutcome(
                          KeyframeKnowledge.NotReported::new, ChapterKnowledge.NotReported::new))
                  .isInstanceOfSatisfying(
                      ProbeOutcome.Success.class,
                      success -> assertThat(success.mediaProbe().videoCodec()).isEqualTo("h264"));
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should complete without probing when a matching outcome is already stored")
  void shouldCompleteWithoutProbingWhenAMatchingOutcomeIsAlreadyStored(boolean terminalFailure) {
    var request = request(ProbeVersion.CURRENT);
    outcomes.store(
        MediaFileContainerInfo.builder()
            .mediaFileId(mediaFile.getId())
            .snapshot(request.snapshot())
            .probeVersion(ProbeVersion.CURRENT)
            .probeError(terminalFailure ? ProbeError.INVALID_MEDIA : null)
            .build());

    var result = execution().execute(request, ProbePriority.BACKGROUND);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    assertThat(producer.probeCount()).isZero();
    assertThat(outcomes.publications()).isEmpty();
  }

  @Test
  @DisplayName("Should complete without probing when the media file no longer exists")
  void shouldCompleteWithoutProbingWhenTheMediaFileNoLongerExists() {
    var request = request(ProbeVersion.CURRENT).toBuilder().mediaFileId(UUID.randomUUID()).build();

    var result = execution().execute(request, ProbePriority.BACKGROUND);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    assertThat(producer.probeCount()).isZero();
  }

  @Test
  @DisplayName("Should report the source removed without probing when the source file has vanished")
  void shouldReportTheSourceRemovedWithoutProbingWhenTheSourceFileHasVanished() throws IOException {
    var request = request(ProbeVersion.CURRENT);
    Files.delete(source);

    var result = execution().execute(request, ProbePriority.BACKGROUND);

    assertThat(result).isEqualTo(new ProbeExecutionResult.SourceRemoved());
    assertThat(producer.probeCount()).isZero();
  }

  @Test
  @DisplayName(
      "Should report the source removed without publishing when it vanished during the probe")
  void shouldReportTheSourceRemovedWithoutPublishingWhenItVanishedDuringTheProbe() {
    var request = request(ProbeVersion.CURRENT);
    producer.runDuringProbe(() -> delete(source));

    var result = execution().execute(request, ProbePriority.BACKGROUND);

    assertThat(result).isEqualTo(new ProbeExecutionResult.SourceRemoved());
    assertThat(outcomes.publications()).isEmpty();
  }

  @Test
  @DisplayName("Should complete without probing when the requested version is newer than supported")
  void shouldCompleteWithoutProbingWhenTheRequestedVersionIsNewerThanSupported() {
    var result = execution().execute(request(ProbeVersion.CURRENT + 1), ProbePriority.BACKGROUND);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    assertThat(producer.probeCount()).isZero();
  }

  @Test
  @DisplayName(
      "Should report a source change with the observed snapshot when it changed before probing")
  void shouldReportASourceChangeWithTheObservedSnapshotWhenItChangedBeforeProbing()
      throws IOException {
    var request = request(ProbeVersion.CURRENT);
    Files.write(source, new byte[] {4, 5}, StandardOpenOption.APPEND);

    var result = execution().execute(request, ProbePriority.BACKGROUND);

    assertThat(result)
        .isEqualTo(
            new ProbeExecutionResult.SourceChanged(
                request.toBuilder().snapshot(snapshot(source)).build()));
    assertThat(producer.probeCount()).isZero();
  }

  @Test
  @DisplayName("Should reschedule at the current version when the request is older")
  void shouldRescheduleAtTheCurrentVersionWhenTheRequestIsOlder() {
    var request = request(ProbeVersion.CURRENT - 1);

    var result = execution().execute(request, ProbePriority.BACKGROUND);

    assertThat(result)
        .isEqualTo(
            new ProbeExecutionResult.Rescheduled(
                request.toBuilder().probeVersion(ProbeVersion.CURRENT).build()));
    assertThat(producer.probeCount()).isZero();
  }

  @Test
  @DisplayName("Should report a source change without publishing when it changed during the probe")
  void shouldReportASourceChangeWithoutPublishingWhenItChangedDuringTheProbe() {
    var request = request(ProbeVersion.CURRENT);
    producer.runDuringProbe(() -> append(source));

    var result = execution().execute(request, ProbePriority.BACKGROUND);

    assertThat(result)
        .isEqualTo(
            new ProbeExecutionResult.SourceChanged(
                request.toBuilder().snapshot(snapshot(source)).build()));
    assertThat(outcomes.publications()).isEmpty();
  }

  @Test
  @DisplayName("Should keep the newer outcome when a stale duplicate request runs")
  void shouldKeepTheNewerOutcomeWhenAStaleDuplicateRequestRuns() {
    var staleRequest = request(ProbeVersion.CURRENT);
    append(source);
    var currentRequest = request(ProbeVersion.CURRENT);
    var execution = execution();
    execution.execute(currentRequest, ProbePriority.BACKGROUND);

    var result = execution.execute(staleRequest, ProbePriority.BACKGROUND);

    assertThat(result).isEqualTo(new ProbeExecutionResult.SourceChanged(currentRequest));
    assertThat(producer.probeCount()).isOne();
    assertThat(outcomes.publications()).singleElement();
    assertThat(outcomes.findByMediaFileId(mediaFile.getId()))
        .hasValueSatisfying(
            stored -> assertThat(stored.getSnapshot()).isEqualTo(currentRequest.snapshot()));
  }

  @Test
  @DisplayName("Should propagate failure without publishing when the producer fails transiently")
  void shouldPropagateFailureWithoutPublishingWhenTheProducerFailsTransiently() {
    producer.failWith(
        new ProbeExecutionException(ItemFailureReason.TEMPORARY, "worker unavailable"));
    var execution = execution();
    var request = request(ProbeVersion.CURRENT);

    assertThatThrownBy(() -> execution.execute(request, ProbePriority.BACKGROUND))
        .isInstanceOf(ProbeExecutionException.class);
    assertThat(outcomes.publications()).isEmpty();
  }

  @Test
  @DisplayName("Should defer without publishing when every compatible worker is busy")
  void shouldDeferWithoutPublishingWhenEveryCompatibleWorkerIsBusy() {
    producer.failWith(new ProbeWorkersBusyException("All compatible workers are busy"));
    var execution = execution();
    var request = request(ProbeVersion.CURRENT);

    var result = execution.execute(request, ProbePriority.BACKGROUND);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Deferred(request));
    assertThat(outcomes.publications()).isEmpty();
  }

  private ProbeExecution execution() {
    return ProbeExecution.builder()
        .mediaFiles(mediaFiles)
        .reader(new PersistedProbeReader(outcomes))
        .producer(producer)
        .fileSystem(FileSystems.getDefault())
        .outcomes(outcomes)
        .packetScans(packetScans)
        .transactionManager(transactionManager)
        .playbackProbeClaims(playbackProbeClaims)
        .build();
  }

  private ProbeTaskRequest request(int probeVersion) {
    return ProbeTaskRequest.builder()
        .mediaFileId(mediaFile.getId())
        .libraryId(mediaFile.getLibraryId())
        .filepathUri(mediaFile.getFilepathUri())
        .snapshot(snapshot(source))
        .probeVersion(probeVersion)
        .build();
  }

  private static ProbeExecutionRequest anyRequestFor(Path path) {
    return ProbeExecutionRequest.builder()
        .sourcePath(path)
        .attemptId(UUID.randomUUID())
        .probeVersion(ProbeVersion.CURRENT)
        .priority(ProbePriority.BACKGROUND)
        .build();
  }

  private static SourceFileSnapshot snapshot(Path path) {
    try {
      var attributes = Files.readAttributes(path, BasicFileAttributes.class);
      return new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant());
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static void delete(Path path) {
    try {
      Files.delete(path);
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
