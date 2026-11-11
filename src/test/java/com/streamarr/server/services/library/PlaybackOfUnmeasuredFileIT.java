package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.BitrateScanChunkFixture.measured;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.config.KeyframePacketScanScheduler;
import com.streamarr.server.domain.streaming.BitrateScanChunkRequest;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;
import com.streamarr.server.services.streaming.ProbeCapacity;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

@TestPropertySource(properties = "PROBE_KEYFRAME_PACKET_SCAN_SCHEDULER_THREADS=1")
@Tag("IntegrationTest")
@DisplayName("Playback that copies a file without a measurement")
class PlaybackOfUnmeasuredFileIT extends AbstractProbeSchedulerIntegrationTest {

  private static final long BOUND_SECONDS = 30;
  private static final int VIDEO = 0;
  private static final long TICKS_PER_KEYFRAME = 90_000;
  private static final long VIDEO_BYTES_PER_INTERVAL = 1_000;
  private static final long PROBED_VIDEO_BITRATE = 8_000_000;
  private static final int ONE_CHUNK = 60;
  // One chunk holds at most 4,096 keyframe intervals, so these keyframes need two chunks.
  private static final int TWO_CHUNKS = 4_100;
  private static final String BITRATE_SCAN_CHUNK = "bitrate scan chunk";
  private static final String KEYFRAME_PACKET_SCAN = "keyframe packet scan";

  @Autowired private StreamingService streamingService;
  @Autowired private HlsPlaylistService playlists;
  @Autowired private BitrateScanRepository bitrateScans;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private DSLContext dsl;

  @TestBean TranscodeExecutor transcodeExecutor;
  @TestBean SegmentStore segmentStore;
  @TestBean PlaybackAuthorityGate authorityGate;

  static TranscodeExecutor transcodeExecutor() {
    return new FakeTranscodeExecutor();
  }

  static SegmentStore segmentStore() {
    return new FakeSegmentStore();
  }

  static PlaybackAuthorityGate authorityGate() {
    return (_, _) -> true;
  }

  private record StartedScan(String kind, Path source) {}

  private final List<StartedScan> startedScans = new CopyOnWriteArrayList<>();
  private final BlockingQueue<ExecutionComplete> completedScans = new LinkedBlockingQueue<>();
  private final Semaphore capacityReads = new Semaphore(0);
  private final AtomicReference<ProbeCapacity> scanCapacity =
      new AtomicReference<>(ProbeSchedulerStarter.FAKE_WORKERS.probeCapacity());
  private final List<UUID> streamSessions = new ArrayList<>();
  private Consumer<BitrateScanChunkRequest> duringChunk = _ -> {};
  private KeyframePacketScanScheduler scanScheduler;

  @AfterEach
  void stopScanSchedulerAndStreamSessions() {
    if (scanScheduler != null) {
      scanScheduler.stop();
    }

    streamSessions.forEach(streamingService::destroySession);
  }

  @Test
  @DisplayName(
      "Should run the file's bitrate scan behind keyframe packet scans and ahead of every other"
          + " bitrate scan when a stream session copies the unmeasured file")
  void
      shouldRunTheFilesBitrateScanBehindKeyframePacketScansAndAheadOfEveryOtherBitrateScanWhenAStreamSessionCopiesTheUnmeasuredFile()
          throws Exception {
    var files = requestUnchangedFiles(4);
    var withoutProbedBitrate = files.get(0);
    var withProbedBitrate = files.get(1);
    var copied = files.get(2);
    var withoutKeyframePositions = files.get(3);
    store(withoutProbedBitrate, copyableOutcome(OptionalLong.empty(), ONE_CHUNK));
    store(withProbedBitrate, copyableOutcome(OptionalLong.of(PROBED_VIDEO_BITRATE), ONE_CHUNK));
    store(copied, copyableOutcome(OptionalLong.of(PROBED_VIDEO_BITRATE), ONE_CHUNK));
    store(withoutKeyframePositions, keyframePacketScanRequired());

    createCopySession(copied);
    startScanScheduler();
    awaitCompletedScans(4);

    assertThat(startedScans)
        .containsExactly(
            new StartedScan(KEYFRAME_PACKET_SCAN, sourceOf(withoutKeyframePositions)),
            new StartedScan(BITRATE_SCAN_CHUNK, sourceOf(copied)),
            new StartedScan(BITRATE_SCAN_CHUNK, sourceOf(withoutProbedBitrate)),
            new StartedScan(BITRATE_SCAN_CHUNK, sourceOf(withProbedBitrate)));
  }

  @Test
  @DisplayName(
      "Should run the rest of the file's running bitrate scan before the next bitrate scan when a"
          + " stream session copies the unmeasured file during a chunk")
  void
      shouldRunTheRestOfTheFilesRunningBitrateScanBeforeTheNextBitrateScanWhenAStreamSessionCopiesTheUnmeasuredFileDuringAChunk()
          throws Exception {
    var files = requestUnchangedFiles(2);
    var copied = files.getFirst();
    var next = files.getLast();
    store(copied, copyableOutcome(OptionalLong.empty(), TWO_CHUNKS));
    store(next, copyableOutcome(OptionalLong.empty(), ONE_CHUNK));
    var firstChunkRuns = new CountDownLatch(1);
    var finishFirstChunk = new CompletableFuture<Void>();
    duringChunk =
        chunk -> {
          if (chunk.firstChunk() && chunk.sourcePath().equals(sourceOf(copied))) {
            firstChunkRuns.countDown();
            finishFirstChunk.orTimeout(BOUND_SECONDS, TimeUnit.SECONDS).join();
          }
        };
    startScanScheduler();
    assertThat(firstChunkRuns.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

    createCopySession(copied);
    finishFirstChunk.complete(null);
    awaitCompletedScans(3);

    assertThat(startedScans)
        .containsExactly(
            new StartedScan(BITRATE_SCAN_CHUNK, sourceOf(copied)),
            new StartedScan(BITRATE_SCAN_CHUNK, sourceOf(copied)),
            new StartedScan(BITRATE_SCAN_CHUNK, sourceOf(next)));
  }

  @Test
  @DisplayName(
      "Should start the stream session at the unmeasured cost when a chunk of the file's bitrate"
          + " scan runs")
  void shouldStartTheStreamSessionAtTheUnmeasuredCostWhenAChunkOfTheFilesBitrateScanRuns()
      throws Exception {
    var copied = requestUnchangedFiles(1).getFirst();
    store(copied, copyableOutcome(OptionalLong.of(PROBED_VIDEO_BITRATE), TWO_CHUNKS));
    var chunkRuns = new CountDownLatch(1);
    var finishChunk = new CompletableFuture<Void>();
    duringChunk =
        _ -> {
          chunkRuns.countDown();
          finishChunk.orTimeout(BOUND_SECONDS, TimeUnit.SECONDS).join();
        };
    startScanScheduler();
    assertThat(chunkRuns.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

    var session = createCopySession(copied);
    var completedBeforeTheSessionStarted = List.copyOf(completedScans);
    finishChunk.complete(null);

    assertThat(completedBeforeTheSessionStarted).as("The chunk still ran").isEmpty();
    assertThat(multivariantPlaylist(session)).contains("AVERAGE-BANDWIDTH=8000000,");
  }

  @Test
  @DisplayName(
      "Should leave the copied file's bitrate scan unclaimed until a keyframe packet scan unit"
          + " frees when no unit is free")
  void
      shouldLeaveTheCopiedFilesBitrateScanUnclaimedUntilAKeyframePacketScanUnitFreesWhenNoUnitIsFree()
          throws Exception {
    var copied = requestUnchangedFiles(1).getFirst();
    store(copied, copyableOutcome(OptionalLong.of(PROBED_VIDEO_BITRATE), ONE_CHUNK));
    freeKeyframePacketScanUnits(0);

    createCopySession(copied);
    startScanScheduler();
    // Each claim reads the capacity first, so the second read follows a whole claim.
    assertThat(capacityReads.tryAcquire(2, BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
    var waitingWithoutAFreeUnit = waitingScans();
    freeKeyframePacketScanUnits(1);
    awaitCompletedScans(1);

    assertThat(waitingWithoutAFreeUnit).isOne();
    assertThat(startedScans).containsExactly(new StartedScan(BITRATE_SCAN_CHUNK, sourceOf(copied)));
  }

  @Test
  @DisplayName(
      "Should cost the next stream session the measured average and keep the first stream"
          + " session's cost when the file's bitrate scan finishes")
  void shouldCostTheNextStreamSessionTheMeasuredAverageWhenTheFilesBitrateScanFinishes()
      throws Exception {
    var copied = requestUnchangedFiles(1).getFirst();
    store(copied, copyableOutcome(OptionalLong.of(PROBED_VIDEO_BITRATE), TWO_CHUNKS));
    var first = createCopySession(copied);

    startScanScheduler();
    awaitCompletedScans(2);
    var next = createCopySession(copied);

    // 4,100,000 payload bytes, 4,100 sample entries of 16 bytes and 684 fragments with 96 bytes of
    // headers each, over 4,100 s.
    assertThat(multivariantPlaylist(next)).contains("AVERAGE-BANDWIDTH=8257,");
    assertThat(multivariantPlaylist(first)).contains("AVERAGE-BANDWIDTH=8000000,");
  }

  @Override
  ProbeSchedulerStarter probeSchedulerStarter() {
    return super.probeSchedulerStarter().toBuilder()
        .probeCapacity(
            () -> {
              capacityReads.release();
              return scanCapacity.get();
            })
        .build();
  }

  private void freeKeyframePacketScanUnits(int units) {
    scanCapacity.set(
        ProbeCapacity.builder()
            .keyframePacketScanCapacity(1)
            .freeKeyframePacketScanUnits(units)
            .build());
  }

  // Stores the outcome as the file's probe does, which requests the scan that the outcome needs.
  private void store(ProbeTaskRequest file, ProbeOutcome outcome) {
    var worker = new FakeFfprobeService();
    worker.answerWith(outcome);
    probeExecution.toBuilder().producer(worker).build().execute(file, ProbePriority.BACKGROUND);
  }

  private StreamSession createCopySession(ProbeTaskRequest file) {
    var session =
        accepted(
            streamingService.createSession(
                createStreamSessionCommandBuilder(file.mediaFileId(), UUID.randomUUID()).build()));
    streamSessions.add(session.getSessionId());
    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo())
        .as("The stream session copies the video")
        .isTrue();
    return session;
  }

  private String multivariantPlaylist(StreamSession session) {
    return playlists.generateMultivariantPlaylist(session, "test-token");
  }

  // A worker that runs one scan at a time and measures every keyframe interval with one packet.
  private void startScanScheduler() {
    var meters = new SimpleMeterRegistry();
    scanScheduler =
        scanSchedulerBuilder()
            .execution(
                KeyframePacketScanExecution.builder()
                    .reader(reader)
                    .scanner(
                        request -> {
                          startedScans.add(
                              new StartedScan(KEYFRAME_PACKET_SCAN, request.sourcePath()));
                          return copyableOutcome(OptionalLong.empty(), ONE_CHUNK);
                        })
                    .fileSystem(FileSystems.getDefault())
                    .outcomes(outcomes)
                    .meterRegistry(meters)
                    .build())
            .bitrateScanExecution(
                BitrateScanExecution.builder()
                    .scans(bitrateScans)
                    .scanner(
                        chunk -> {
                          startedScans.add(new StartedScan(BITRATE_SCAN_CHUNK, chunk.sourcePath()));
                          duringChunk.accept(chunk);
                          return measured(chunk, _ -> VIDEO_BYTES_PER_INTERVAL);
                        })
                    .fileSystem(FileSystems.getDefault())
                    .meterRegistry(meters)
                    .build())
            .meterRegistry(meters)
            .clock(Clock.systemUTC())
            .listeners(List.of(collectingCompletedScans()))
            .build();
    scanScheduler.start();
  }

  private AbstractSchedulerListener collectingCompletedScans() {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete executionComplete) {
        completedScans.add(executionComplete);
      }
    };
  }

  private void awaitCompletedScans(int count) throws InterruptedException {
    for (var completed = 0; completed < count; completed++) {
      assertThat(completedScans.poll(BOUND_SECONDS, TimeUnit.SECONDS)).isNotNull();
    }
  }

  private int waitingScans() {
    return dsl.fetchCount(
        DSL.table(DSL.name(KeyframePacketScanScheduler.TABLE_NAME)),
        DSL.field(DSL.name("picked"), Boolean.class).isFalse());
  }

  /**
   * A Matroska file of one H.264 video stream that a stream session copies, with a keyframe every
   * second.
   */
  private static ProbeOutcome.Success copyableOutcome(OptionalLong videoBitrate, int keyframes) {
    return outcome(
        videoBitrate,
        keyframes,
        new KeyframeKnowledge.Known(
            KeyframePositions.builder()
                .streamIndex(VIDEO)
                .timeBaseNumerator(1)
                .timeBaseDenominator(90_000)
                .source(KeyframePositionSource.CONTAINER_INDEX)
                .keyframes(
                    IntStream.range(0, keyframes)
                        .mapToObj(
                            index ->
                                new KeyframePosition(
                                    index * TICKS_PER_KEYFRAME, index * TICKS_PER_KEYFRAME))
                        .toList())
                .build()));
  }

  private static ProbeOutcome.Success keyframePacketScanRequired() {
    return outcome(
        OptionalLong.empty(),
        ONE_CHUNK,
        new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX));
  }

  private static ProbeOutcome.Success outcome(
      OptionalLong videoBitrate, int seconds, KeyframeKnowledge keyframes) {
    var probe =
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(seconds))
            .keyframes(keyframes)
            .audioCodec(null)
            .build();
    return ProbeFixture.completeProbeBuilder(probe)
        .streams(List.of(ProbeFixture.videoStreamBuilder(probe).bitrate(videoBitrate).build()))
        .build();
  }

  private static Path sourceOf(ProbeTaskRequest file) {
    return FilepathCodec.decode(file.filepathUri());
  }
}
