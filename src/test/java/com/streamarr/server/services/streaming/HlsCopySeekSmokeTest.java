package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRegistry;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.support.OutcomeTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Real-FFmpeg proof that a stream copy seeks to the keyframe that opens the requested segment. A
 * replacement attempt at segment 2 must deliver the same video samples as the attempt that copied
 * the source from its start.
 */
@Tag("SmokeTest")
@DisplayName("HLS Stream Copy Seek Smoke Tests")
class HlsCopySeekSmokeTest {

  private static final int SEGMENT_SECONDS = 6;
  private static final int SEEK_SEGMENT = 2;
  private static final String SEEK_SEGMENT_NAME = SegmentNames.mediaSegmentName(SEEK_SEGMENT);

  // 24 fps with keyframes forced off the 6 s grid: each 6 s interval holds one keyframe, and the
  // copy segments last 7.5, 5.5, 7.5, 5.5, 5 and 5 s.
  private static final List<String> IRREGULAR_MATROSKA =
      source(
          "rate=24",
          List.of("-force_key_frames", "0,7.5,13,20.5,26,31"),
          List.of("-f", "matroska"));

  // 4 fps with two B-frames: each keyframe decodes 0.5 s before it presents. The keyframe that
  // opens segment 2 presents at 12 s, on the boundary, and decodes at 11.5 s. A seek to 12 s
  // reads past it.
  private static final List<String> BOUNDARY_MPEG_TS =
      source(
          "rate=4",
          List.of("-bf", "2", "-force_key_frames", "0,6,12,19,25,30"),
          List.of("-f", "mpegts"));

  private FakeMediaFileRepository mediaFileRepository;
  private FakeMediaFileContainerInfoRepository probeRepository;
  private LocalSegmentStore segmentStore;
  private HlsStreamingService streamingService;
  private HlsPlaylistService playlistService;
  private SegmentDeliveryCoordinator coordinator;
  private RemoteFfprobeService probes;
  private RemoteTranscodeExecutor transcodeExecutor;
  private WorkerStreamingSmokeFixture workerFixture;
  private Path sourceRoot;
  @TempDir private Path temporaryDirectory;

  @BeforeEach
  void setUp() throws Exception {
    sourceRoot = Files.createDirectory(temporaryDirectory.resolve("media"));
    segmentStore =
        new LocalSegmentStore(Files.createDirectory(temporaryDirectory.resolve("segments")));
    workerFixture =
        WorkerStreamingSmokeFixture.builder()
            .sourceRoot(sourceRoot)
            .segmentStore(segmentStore)
            .build();
    workerFixture.start();
    probes =
        new RemoteFfprobeService(
            workerFixture.workerSessions(), workerFixture.sourceNamespaceId(), sourceRoot);
    transcodeExecutor =
        new RemoteTranscodeExecutor(
            workerFixture.workerSessions(), workerFixture.sourceNamespaceId(), sourceRoot);
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(Duration.ofSeconds(SEGMENT_SECONDS))
            .sessionTimeout(Duration.ofSeconds(60))
            .producerStallThreshold(Duration.ofSeconds(10))
            .build();
    var sessionRegistry = new InMemoryStreamSessionRegistry();
    var producerLifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(sessionRegistry)
            .sessionMutex(new MutexFactory<>())
            .build();
    coordinator =
        SegmentDeliveryCoordinator.builder()
            .segmentStore(segmentStore)
            .producerLifecycle(producerLifecycle)
            .build();
    mediaFileRepository = new FakeMediaFileRepository();
    probeRepository = new FakeMediaFileContainerInfoRepository();
    streamingService =
        HlsStreamingService.builder()
            .mediaFileRepository(mediaFileRepository)
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(
                new PlaybackProbeService(
                    new PersistedProbeReader(probeRepository), new CapturingEventPublisher()))
            .transcodeDecisionService(
                new TranscodeDecisionService(properties, new SimpleMeterRegistry()))
            .qualityLadderService(new QualityLadderService())
            .properties(properties)
            .authorityGate((_, _) -> true)
            .runtimeRegistry(sessionRegistry)
            .producerLifecycle(producerLifecycle)
            .deliveryCoordinator(coordinator)
            .build();
    playlistService = new HlsPlaylistService(properties);
  }

  @AfterEach
  void tearDown() {
    try {
      if (streamingService != null) {
        streamingService
            .getAllSessions()
            .forEach(session -> streamingService.destroySession(session.getSessionId()));
      }
    } finally {
      try {
        if (workerFixture != null) {
          workerFixture.close();
        }
      } finally {
        if (segmentStore != null) {
          segmentStore.shutdown();
        }
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("irregularKeyframeSources")
  @DisplayName(
      "Should deliver the requested segment from its stored keyframe when a stream copy seeks")
  void shouldDeliverTheRequestedSegmentFromItsStoredKeyframeWhenAStreamCopySeeks(
      String fileName,
      List<String> mediaArguments,
      KeyframePositionSource keyframeSource,
      List<Double> copySegmentSeconds)
      throws Exception {
    var source = workerFixture.worker().generateMedia(sourceRoot.resolve(fileName), mediaArguments);
    var positions = storeProbeWithKeyframes(source);
    var session = createSession(source);

    assertThat(positions.source()).isEqualTo(keyframeSource);
    var sessionId = session.getSessionId();

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(gridIntervalCount(session))
        .as("the AAC tail forms a grid interval past the last keyframe")
        .isEqualTo(copySegmentSeconds.size() + 1);
    assertThat(extinfSeconds(playlistService.generateMediaPlaylist(session, "smoke-token")))
        .zipSatisfy(
            copySegmentSeconds,
            (advertised, expected) -> assertThat(advertised).isCloseTo(expected, within(0.1)));

    var lastSegment = SegmentNames.mediaSegmentName(copySegmentSeconds.size() - 1);
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> segmentStore.segmentExists(sessionId, lastSegment));
    var fromStart = videoPresentationTimes(sessionId);

    killProducerAndDropSegmentsFrom(session, SEEK_SEGMENT, copySegmentSeconds.size());
    var delivery =
        coordinator.deliver(sessionId, StreamSession.defaultVariant(), SEEK_SEGMENT_NAME);

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(videoPresentationTimes(sessionId))
        .as("the replacement's segment 2 holds the same video as the copy from the start")
        .isEqualTo(fromStart);
  }

  static Stream<Arguments> irregularKeyframeSources() {
    return Stream.of(
        Arguments.of(
            "irregular-keyframes.mkv",
            IRREGULAR_MATROSKA,
            KeyframePositionSource.CONTAINER_INDEX,
            List.of(7.5, 5.5, 7.5, 5.5, 5.0, 5.0)),
        Arguments.of(
            "boundary-keyframes.ts",
            BOUNDARY_MPEG_TS,
            KeyframePositionSource.PACKET_SCAN,
            List.of(6.0, 6.0, 7.0, 6.0, 5.0, 6.0)));
  }

  // The AAC encoder's priming samples make each container about 23 ms longer than its 36 s video.
  // That tail forms a seventh grid interval without a keyframe, which joins the last copy segment.
  private static List<String> source(
      String frameRate, List<String> keyframeArguments, List<String> formatArguments) {
    var arguments =
        new ArrayList<>(
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc=size=160x90:duration=36:" + frameRate,
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:duration=36",
                "-c:v",
                "libx264",
                "-g",
                "1000",
                "-keyint_min",
                "1000",
                "-sc_threshold",
                "0"));
    arguments.addAll(keyframeArguments);
    arguments.addAll(List.of("-c:a", "aac", "-shortest"));
    arguments.addAll(formatArguments);
    return List.copyOf(arguments);
  }

  // An MPEG-TS source has no index, so the probe asks for a packet scan.
  private KeyframePositions storeProbeWithKeyframes(Path source) {
    var request =
        ProbeExecutionRequest.builder()
            .sourcePath(source)
            .attemptId(UUID.randomUUID())
            .probeVersion(ProbeVersion.CURRENT)
            .build();
    var outcome = probes.probe(request);
    if (outcome instanceof ProbeOutcome.Success success
        && success.keyframes() instanceof KeyframeKnowledge.PacketScanRequired) {
      outcome = probes.scanKeyframePackets(request);
    }

    assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
    var success = (ProbeOutcome.Success) outcome;
    assertThat(success.keyframes()).isInstanceOf(KeyframeKnowledge.Known.class);
    probeRepository.setDefaultOutcome(success);
    return ((KeyframeKnowledge.Known) success.keyframes()).positions();
  }

  private StreamSession createSession(Path source) throws Exception {
    var file =
        mediaFileRepository.save(
            MediaFile.builder()
                .filepathUri(FilepathCodec.encode(source))
                .filename(source.getFileName().toString())
                .status(MediaFileStatus.MATCHED)
                .size(Files.size(source))
                .build());
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();
    return OutcomeTestSupport.accepted(
        streamingService.createSession(
            createStreamSessionCommand(file.getId(), UUID.randomUUID(), options)));
  }

  private void killProducerAndDropSegmentsFrom(
      StreamSession session, int firstMissingIndex, int segmentCount) throws Exception {
    workerFixture.worker().killProducer(session.getHandle().orElseThrow().attemptId());
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                !transcodeExecutor.isRunning(
                    session.getSessionId(), StreamSession.defaultVariant()));
    var outputDirectory = segmentStore.getOutputDirectory(session.getSessionId());
    for (var index = firstMissingIndex; index < segmentCount; index++) {
      Files.deleteIfExists(outputDirectory.resolve(SegmentNames.mediaSegmentName(index)));
    }
  }

  private List<Double> videoPresentationTimes(UUID sessionId) throws Exception {
    var media =
        Files.write(
            Files.createTempFile(temporaryDirectory, "segment", ".mp4"),
            Fmp4Fixture.withInitializationSegment(segmentStore, sessionId, SEEK_SEGMENT_NAME));
    return workerFixture.worker().packetTimestamps(media);
  }

  private static int gridIntervalCount(StreamSession session) {
    return new MediaSegmentTimeline.PlainGrid(
            session.getMediaProbe().duration(), Duration.ofSeconds(SEGMENT_SECONDS))
        .mediaSegmentCount();
  }

  private static List<Double> extinfSeconds(String playlist) {
    return playlist
        .lines()
        .filter(line -> line.startsWith("#EXTINF:"))
        .map(line -> Double.parseDouble(line.substring("#EXTINF:".length(), line.indexOf(','))))
        .toList();
  }
}
