package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.PersistedProbeFixture.storedProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.ffmpeg.LocalFfmpegProcessManager;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRegistry;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.support.OutcomeTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("SmokeTest")
@DisplayName("HLS Streaming Smoke Tests")
class HlsStreamingSmokeTest {

  private static final Path TEST_VIDEO =
      Path.of("src/test/resources/BigBuckBunny_320x180_10s.mp4").toAbsolutePath();

  private FakeMediaFileRepository mediaFileRepository;
  private LocalSegmentStore segmentStore;
  private HlsStreamingService streamingService;
  private HlsPlaylistService playlistService;
  private Path segmentBaseDir;
  private WorkerStreamingSmokeFixture workerFixture;
  private RecordingProcessManager processManager;

  @BeforeAll
  static void checkPrerequisites() {
    assumeTrue(isFfmpegAvailable(), "FFmpeg not found on PATH");
    assumeTrue(Files.exists(TEST_VIDEO), "Test video not found: " + TEST_VIDEO);
  }

  private static boolean isFfmpegAvailable() {
    try {
      var process = new ProcessBuilder("ffmpeg", "-version").start();
      return process.waitFor() == 0;
    } catch (Exception _) {
      return false;
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    segmentBaseDir = Files.createTempDirectory("streamarr-smoke-");
    segmentStore = new LocalSegmentStore(segmentBaseDir);
    processManager = new RecordingProcessManager();

    workerFixture =
        WorkerStreamingSmokeFixture.builder()
            .processManager(processManager)
            .sourceRoot(TEST_VIDEO.getParent())
            .segmentBaseDir(segmentBaseDir)
            .segmentStore(segmentStore)
            .build();
    var transcodeExecutor =
        new RemoteTranscodeExecutor(
            workerFixture.workerSessions(),
            workerFixture.sourceNamespaceId(),
            TEST_VIDEO.getParent());
    workerFixture.start();
    var outcome =
        new RemoteFfprobeService(
                workerFixture.workerSessions(),
                workerFixture.sourceNamespaceId(),
                TEST_VIDEO.getParent())
            .probe(
                ProbeExecutionRequest.builder()
                    .sourcePath(TEST_VIDEO)
                    .attemptId(UUID.randomUUID())
                    .probeVersion(ProbeVersion.CURRENT)
                    .build());
    assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
    var completeProbe = (ProbeOutcome.Success) outcome;
    var probeRepository = new FakeMediaFileContainerInfoRepository();
    probeRepository.setDefaultOutcome(completeProbe);
    var probeReader = new PersistedProbeReader(probeRepository);

    var decisionService = new TranscodeDecisionService();
    var qualityLadderService = new QualityLadderService();
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();

    mediaFileRepository = new FakeMediaFileRepository();
    var sessionRegistry = new InMemoryStreamSessionRegistry();
    var producerLifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(sessionRegistry)
            .sessionMutex(new MutexFactory<>())
            .build();
    streamingService =
        HlsStreamingService.builder()
            .mediaFileRepository(mediaFileRepository)
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(
                new PlaybackProbeService(probeReader, new CapturingEventPublisher()))
            .transcodeDecisionService(decisionService)
            .qualityLadderService(qualityLadderService)
            .properties(properties)
            .authorityGate((_, _) -> true)
            .runtimeRegistry(sessionRegistry)
            .producerLifecycle(producerLifecycle)
            .deliveryCoordinator(
                SegmentDeliveryCoordinator.builder()
                    .segmentStore(segmentStore)
                    .producerLifecycle(producerLifecycle)
                    .build())
            .build();

    playlistService = new HlsPlaylistService(properties);
  }

  @AfterEach
  void tearDown() {
    try {
      streamingService
          .getAllSessions()
          .forEach(s -> streamingService.destroySession(s.getSessionId()));
    } catch (Exception _) {
      // best-effort cleanup
    }
    if (workerFixture != null) {
      workerFixture.close();
    }

    segmentStore.shutdown();
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder()
        .quality(VideoQuality.AUTO)
        .supportedCodecs(List.of("h264"))
        .build();
  }

  private MediaFile seedMediaFile() {
    var file =
        MediaFile.builder()
            .filepathUri(FilepathCodec.encode(TEST_VIDEO))
            .filename("BigBuckBunny_320x180.mp4")
            .status(MediaFileStatus.MATCHED)
            .size(TEST_VIDEO.toFile().length())
            .build();
    return mediaFileRepository.save(file);
  }

  private StreamSession createSession(UUID mediaFileId, UUID profileId, StreamingOptions options) {
    return OutcomeTestSupport.accepted(
        streamingService.createSession(
            createStreamSessionCommand(mediaFileId, profileId, options)));
  }

  @Test
  @DisplayName("Should detect correct codecs when probing test video")
  void shouldDetectCorrectCodecsWhenProbingTestVideo() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    var probe = session.getMediaProbe();
    assertThat(probe.videoCodec()).isEqualTo("h264");
    assertThat(probe.audioCodec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should detect correct resolution when probing test video")
  void shouldDetectCorrectResolutionWhenProbingTestVideo() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    var probe = session.getMediaProbe();
    assertThat(probe.width()).isEqualTo(320);
    assertThat(probe.height()).isEqualTo(180);
  }

  @Test
  @DisplayName("Should detect valid duration and bitrate when probing test video")
  void shouldDetectValidDurationAndBitrateWhenProbingTestVideo() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    var probe = session.getMediaProbe();
    assertThat(probe.framerate()).isGreaterThan(0);
    assertThat(probe.duration()).isGreaterThan(Duration.ofSeconds(9));
    assertThat(probe.bitrate()).isGreaterThan(0);
  }

  @Test
  @DisplayName("Should choose remux when source codec is compatible")
  void shouldChooseRemuxWhenSourceCodecIsCompatible() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getHandle().orElseThrow().processId()).isEmpty();
    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getTranscodeDecision().containerFormat()).isEqualTo(ContainerFormat.MPEGTS);
  }

  @Test
  @DisplayName("Should start FFmpeg and produce segments when session is created")
  void shouldStartFfmpegAndProduceSegmentsWhenSessionIsCreated() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getHandle()).isPresent();
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);

    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> segmentStore.segmentExists(session.getSessionId(), "segment0.ts"));

    var segmentData = segmentStore.readSegment(session.getSessionId(), "segment0.ts");
    assertThat(segmentData).isNotNull().hasSizeGreaterThan(0);
    assertThat(segmentData[0]).isEqualTo((byte) 0x47);
  }

  @Test
  @DisplayName("Should start multivariant playlist with EXTM3U and no BOM when session is active")
  void shouldStartMultivariantPlaylistWithExtm3uAndNoBomWhenSessionIsActive() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var playlist = playlistService.generateMultivariantPlaylist(session, "smoke-token");

    assertThat(playlist).startsWith("#EXTM3U\n").doesNotContain("\uFEFF");
  }

  @Test
  @DisplayName("Should include stream variant info in multivariant playlist when session is active")
  void shouldIncludeStreamVariantInfoInMultivariantPlaylistWhenSessionIsActive() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var playlist = playlistService.generateMultivariantPlaylist(session, "smoke-token");

    assertThat(playlist)
        .contains("#EXT-X-STREAM-INF:")
        .contains("BANDWIDTH=")
        .contains("RESOLUTION=320x180")
        .contains("CODECS=")
        .contains("stream.m3u8");
  }

  @Test
  @DisplayName("Should include required HLS tags in media playlist when session is active")
  void shouldIncludeRequiredHlsTagsInMediaPlaylistWhenSessionIsActive() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var playlist = playlistService.generateMediaPlaylist(session, "smoke-token");

    assertThat(playlist)
        .startsWith("#EXTM3U\n")
        .contains("#EXT-X-VERSION:3")
        .contains("#EXT-X-TARGETDURATION:6")
        .contains("#EXT-X-MEDIA-SEQUENCE:0")
        .contains("#EXT-X-PLAYLIST-TYPE:VOD")
        .contains("#EXT-X-ENDLIST")
        .doesNotContain("#EXT-X-STREAM-INF")
        .doesNotContain("#EXT-X-MAP");
  }

  @Test
  @DisplayName("Should generate valid segment entries in media playlist when session is active")
  void shouldGenerateValidSegmentEntriesInMediaPlaylistWhenSessionIsActive() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var playlist = playlistService.generateMediaPlaylist(session, "smoke-token");

    var extinfLines = playlist.lines().filter(l -> l.startsWith("#EXTINF:")).toList();
    assertThat(extinfLines).isNotEmpty();

    for (var line : extinfLines) {
      var durationStr = line.replace("#EXTINF:", "").replace(",", "");
      var duration = Double.parseDouble(durationStr);
      assertThat(duration).isGreaterThan(0);
      assertThat(Math.round(duration)).isLessThanOrEqualTo(6);
    }

    var segmentLines =
        playlist
            .lines()
            .filter(l -> l.startsWith("segment") && l.endsWith(".ts?t=smoke-token"))
            .toList();
    assertThat(segmentLines).hasSizeGreaterThan(1);
    assertThat(segmentLines.getFirst()).isEqualTo("segment0.ts?t=smoke-token");
  }

  @Test
  @DisplayName("Should terminate FFmpeg process when session is destroyed")
  void shouldTerminateFfmpegProcessWhenSessionIsDestroyed() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);
    var sessionId = session.getSessionId();

    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> segmentStore.segmentExists(sessionId, "segment0.ts"));

    var handle = session.getHandle().orElseThrow();
    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);
    var process = processManager.lastProcessFor(sessionId).orElseThrow();

    streamingService.destroySession(sessionId);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(process.isAlive()).isFalse());
  }

  @Test
  @DisplayName("Should remove session state when session is destroyed")
  void shouldRemoveSessionStateWhenSessionIsDestroyed() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);
    var sessionId = session.getSessionId();

    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> segmentStore.segmentExists(sessionId, "segment0.ts"));

    streamingService.destroySession(sessionId);

    assertThat(streamingService.accessSession(playbackRequest(session))).isEmpty();
  }

  @Test
  @DisplayName("Should not deadlock when FFmpeg produces verbose stderr output")
  void shouldNotDeadlockWhenFfmpegProducesVerboseStderrOutput() {
    var sessionId = UUID.randomUUID();
    var processManager = new LocalFfmpegProcessManager();

    // -loglevel debug produces ~200KB+ of stderr for a 10s video, well beyond the ~64KB pipe
    // buffer.
    // Without stderr draining, FFmpeg blocks on write() and the process never exits.
    var command =
        List.of("ffmpeg", "-loglevel", "debug", "-i", TEST_VIDEO.toString(), "-f", "null", "-");

    var process =
        processManager.startProcess(sessionId, "deadlock-test", command, TEST_VIDEO.getParent());

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(process.isAlive()).isFalse();
              assertThat(process.exitValue()).isZero();
            });

    processManager.stopProcess(sessionId);
  }
}
