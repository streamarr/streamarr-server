package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.config.KeyframePacketScanScheduler;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.BitrateScanChunkRequest;
import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.LoopbackProbeWorker;
import com.streamarr.server.fixtures.StreamMeasurementFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.fixtures.WorkerProbeResultFixture;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.transcode.v1.KeyframePositionSource;
import com.streamarr.transcode.v1.KeyframePositions;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import com.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * A keyframe packet scan that a worker answers over its session, and what the server then does
 * about the file's measurement (ADR 0054). The file holds 12 s of video and audio with a keyframe
 * every 2 s, so a copy lists two segments of 6 s.
 */
@TestPropertySource(properties = "PROBE_KEYFRAME_PACKET_SCAN_SCHEDULER_THREADS=1")
@Tag("IntegrationTest")
@DisplayName("Keyframe packet scan measurement")
class KeyframePacketScanMeasurementIT extends AbstractProbeSchedulerIntegrationTest {

  private static final int VIDEO = 0;
  private static final int AUDIO = 1;
  private static final int KEYFRAME_COUNT = 6;
  private static final long TICKS_PER_KEYFRAME_INTERVAL = 180_000;

  @Autowired private KeyframePacketScanExecution keyframePacketScanExecution;
  @Autowired private BitrateScanRepository bitrateScans;
  @Autowired private MediaFileContainerInfoRepository outcomes;

  private final BlockingQueue<ExecutionComplete> keyframeScanCompletions =
      new LinkedBlockingQueue<>();
  private final BlockingQueue<BitrateScanChunkRequest> bitrateScanChunks =
      new LinkedBlockingQueue<>();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private KeyframePacketScanScheduler scanScheduler;

  @AfterEach
  void stopScanScheduler() {
    if (scanScheduler != null) {
      scanScheduler.stop();
    }
  }

  @Test
  @DisplayName(
      "Should scan the file's bitrates on its scanned keyframes when its keyframe packet scan"
          + " reported no measurement")
  void shouldScanTheFilesBitratesOnItsScannedKeyframesWhenItsKeyframePacketScanReportedNone()
      throws Exception {
    scanKeyframePackets(KeyframePacketScanMeasurementIT::replyWithoutMeasurement);

    var chunk = bitrateScanChunks.poll(30, TimeUnit.SECONDS);

    assertThat(chunk).as("the first chunk of the file's bitrate scan").isNotNull();
    assertThat(chunk.keyframes()).isEqualTo(scannedKeyframes());
    assertThat(chunk.measuredStreamIndexes()).containsExactly(VIDEO, AUDIO);
  }

  @Test
  @DisplayName(
      "Should store none of the scan's result when a listener of the stored scan fails in the"
          + " scan's transaction")
  void shouldStoreNoneOfTheScansResultWhenAListenerOfTheStoredScanFailsInTheScansTransaction()
      throws Exception {
    var request = probeFileWithoutKeyframes();
    var execution =
        keyframePacketScanExecution.toBuilder()
            .scanner(
                _ ->
                    StreamMeasurementFixture.unmeasured(
                        StreamMeasurementFixture.positions(180_000)))
            .eventPublisher(
                _ -> {
                  throw new IllegalStateException("The scan table is unavailable");
                })
            .build();
    var scan =
        KeyframePacketScanTaskRequest.builder()
            .mediaFileId(request.mediaFileId())
            .filepathUri(request.filepathUri())
            .snapshot(request.snapshot())
            .probeVersion(request.probeVersion())
            .build();

    assertThatIllegalStateException().isThrownBy(() -> execution.execute(scan));

    assertThat(outcomes.findKeyframeKnowledge(request.mediaFileId()))
        .contains(probedWithoutKeyframes().keyframes());
  }

  /** How a worker answers the keyframe packet scan that the server sent it. */
  @FunctionalInterface
  private interface ScanReply {
    void send(LoopbackProbeWorker worker, ProbeRequest scan);
  }

  // Probes a file whose keyframes need a packet scan, and runs that scan to its end against a
  // worker that answers it with the reply.
  private ProbeTaskRequest scanKeyframePackets(ScanReply reply) throws Exception {
    var request = probeFileWithoutKeyframes();
    try (var server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(new FakeSegmentStore())
            .meterRegistry(meterRegistry)
            .build()) {
      server.start();
      try (var worker =
          LoopbackProbeWorker.builder()
              .port(server.port())
              .probeVersion(ProbeVersion.CURRENT)
              .availableSlots(1)
              .backgroundProbeCapacity(1)
              .build()) {
        startScanScheduler(new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, tempDir));
        var scan = worker.nextResponse(Duration.ofSeconds(30)).getStartProbe().getRequest();
        assertThat(scan.getScanKeyframePackets()).isTrue();

        reply.send(worker, scan);

        assertThat(keyframeScanCompletions.poll(30, TimeUnit.SECONDS)).isNotNull();
      }
    }

    return request;
  }

  private ProbeTaskRequest probeFileWithoutKeyframes() throws IOException {
    var request = requestUnchangedFiles(1).getFirst();
    var producer = new FakeFfprobeService();
    producer.answerWith(probedWithoutKeyframes());
    probeExecution.toBuilder()
        .producer(producer)
        .build()
        .execute(request, ProbePriority.BACKGROUND);
    return request;
  }

  // Every bitrate scan chunk finds the workers busy, so a requested bitrate scan stays in the scan
  // table and its first chunk shows what the scan reads.
  private void startScanScheduler(RemoteFfprobeService worker) {
    var bitrateScanExecution =
        BitrateScanExecution.builder()
            .scans(bitrateScans)
            .scanner(
                chunk -> {
                  bitrateScanChunks.add(chunk);
                  throw new ProbeWorkersBusyException("All compatible workers are busy");
                })
            .fileSystem(FileSystems.getDefault())
            .meterRegistry(meterRegistry)
            .build();
    scanScheduler =
        scanSchedulerBuilder()
            .execution(
                keyframePacketScanExecution.toBuilder()
                    .scanner(worker::scanKeyframePackets)
                    .meterRegistry(meterRegistry)
                    .build())
            .bitrateScanExecution(bitrateScanExecution)
            .meterRegistry(meterRegistry)
            .clock(Clock.systemUTC())
            .listeners(List.of(recordingKeyframeScanCompletions()))
            .build();
    scanScheduler.start();
  }

  private AbstractSchedulerListener recordingKeyframeScanCompletions() {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete executionComplete) {
        if (executionComplete
            .getExecution()
            .taskInstance
            .getTaskName()
            .equals(KeyframePacketScanTask.NAME)) {
          keyframeScanCompletions.add(executionComplete);
        }
      }
    };
  }

  private static void replyWithoutMeasurement(LoopbackProbeWorker worker, ProbeRequest scan) {
    sendKeyframes(worker, scan);
    worker.reply(answer(scan, scannedMedia()));
  }

  private static void sendKeyframes(LoopbackProbeWorker worker, ProbeRequest scan) {
    var deltas = new ArrayList<>(inEveryInterval(TICKS_PER_KEYFRAME_INTERVAL));
    deltas.set(0, 0L);
    worker.sendKeyframePositionsPart(
        ProbeKeyframePositionsPart.newBuilder()
            .setProbeAttemptId(scan.getProbeAttemptId())
            .addAllPresentationTimeDeltas(deltas)
            .addAllDecodeTimeDeltas(deltas)
            .build());
  }

  private static <T> List<T> inEveryInterval(T value) {
    return Collections.nCopies(KEYFRAME_COUNT, value);
  }

  // The video stream and the audio stream, with the keyframes that the scan sent in parts.
  private static ProbeMediaInfo.Builder scannedMedia() {
    return WorkerProbeResultFixture.version4MediaBuilder()
        .addStreams(ProbeStreamInfo.newBuilder().setIndex(AUDIO).setCodecType("audio"))
        .setKeyframes(
            ProbeKeyframes.newBuilder()
                .setPositions(
                    KeyframePositions.newBuilder()
                        .setStreamIndex(VIDEO)
                        .setTimeBaseNumerator(1)
                        .setTimeBaseDenominator(90_000)
                        .setSource(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN)
                        .setKeyframeCount(KEYFRAME_COUNT)));
  }

  private static ProbeAttemptResult answer(ProbeRequest scan, ProbeMediaInfo.Builder media) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(scan.getProbeAttemptId())
        .setProbeVersion(scan.getProbeVersion())
        .setMedia(media)
        .build();
  }

  private static List<KeyframePosition> scannedKeyframes() {
    return LongStream.range(0, KEYFRAME_COUNT)
        .map(index -> index * TICKS_PER_KEYFRAME_INTERVAL)
        .mapToObj(ticks -> new KeyframePosition(ticks, ticks))
        .toList();
  }

  // A Matroska file with H.264 video at a probed 8,000,000 bit/s and AAC audio, whose Cues skip
  // keyframes.
  private static ProbeOutcome.Success probedWithoutKeyframes() {
    return ProbeOutcome.Success.builder()
        .container(
            ProbeContainer.builder()
                .format(Optional.of("matroska,webm"))
                .duration(Optional.of(Duration.ofSeconds(12)))
                .build())
        .streams(
            List.of(
                StreamInfo.builder()
                    .index(VIDEO)
                    .codecType("video")
                    .codec(Optional.of("h264"))
                    .codecString(Optional.of("avc1.640028"))
                    .bitrate(OptionalLong.of(8_000_000))
                    .build(),
                StreamInfo.builder()
                    .index(AUDIO)
                    .codecType("audio")
                    .codec(Optional.of("aac"))
                    .build()))
        .keyframes(
            new KeyframeKnowledge.PacketScanRequired(
                KeyframePacketScanReason.INCOMPLETE_CONTAINER_INDEX))
        .chosenVideoStream(
            Optional.of(
                new ChosenVideoStream(
                    VIDEO, VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))))
        .build();
  }
}
