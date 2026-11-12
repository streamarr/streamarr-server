package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.SeekPriorityFixture.seekPriorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.DecimalSeconds;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.ServerSettingsFixture;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRegistry;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.local.SegmentFolderLock;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.support.OutcomeTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("SmokeTest")
@DisplayName("HLS Streaming Smoke Tests")
class HlsStreamingSmokeTest {

  private FakeMediaFileRepository mediaFileRepository;
  private FakeMediaFileContainerInfoRepository probeRepository;
  private LocalSegmentStore segmentStore;
  private HlsStreamingService streamingService;
  private HlsPlaylistService playlistService;
  private Path segmentBaseDir;
  private WorkerStreamingSmokeFixture workerFixture;
  @TempDir private Path temporaryDirectory;
  private Path testVideo;

  @BeforeEach
  void setUp() throws Exception {
    var sourceRoot = Files.createDirectory(temporaryDirectory.resolve("media"));
    testVideo = sourceRoot.resolve("synthetic-h264-aac.mp4");
    segmentBaseDir = Files.createDirectory(temporaryDirectory.resolve("segments"));
    segmentStore = new LocalSegmentStore(segmentBaseDir, new SegmentFolderLock());

    workerFixture =
        WorkerStreamingSmokeFixture.builder()
            .sourceRoot(sourceRoot)
            .ffmpegScript(
                """
                if [[ -f /media/hold-producer ]]; then
                  ffmpeg "$@" || exit $?
                  while IFS= read -r -n 1 input; do
                    if [[ "$input" == q ]]; then exit 0; fi
                  done
                  exit 0
                fi
                if [[ -f /media/verbose-stderr ]]; then
                  for ((line=0; line<4096; line++)); do
                    printf '%064d\n' 0 >&2
                  done
                fi
                """)
            .segmentStore(segmentStore)
            .build();
    var transcodeExecutor =
        new RemoteTranscodeExecutor(
            workerFixture.workerSessions(),
            workerFixture.sourceNamespaceId(),
            testVideo.getParent());
    workerFixture.start();
    workerFixture
        .worker()
        .generateMedia(
            testVideo,
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=320x180:rate=24:duration=10",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=48000:duration=10",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-pix_fmt",
                "yuv420p",
                "-g",
                "48",
                "-keyint_min",
                "48",
                "-sc_threshold",
                "0",
                "-c:a",
                "aac",
                "-b:a",
                "128k",
                "-shortest"));
    probeRepository = new FakeMediaFileContainerInfoRepository();
    probeRepository.setDefaultOutcome(probe(testVideo));

    var qualityLadderService = new QualityLadderService();
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    var decisionService = new TranscodeDecisionService(properties, new SimpleMeterRegistry());

    mediaFileRepository = new FakeMediaFileRepository();
    var sessionRegistry = new InMemoryStreamSessionRegistry();
    var producerLifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(transcodeExecutor)
            .eventPublisher(new CapturingEventPublisher())
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(sessionRegistry)
            .sessionMutex(new MutexFactory<>())
            .serverSettings(ServerSettingsFixture.defaultServerSettings())
            .seekPriority(
                seekPriorityBuilder()
                    .transcodeExecutor(transcodeExecutor)
                    .segmentStore(segmentStore)
                    .runtimeRegistry(sessionRegistry)
                    .properties(properties)
                    .build())
            .build();
    streamingService =
        HlsStreamingService.builder()
            .languagePreferences(noLanguagePreferences())
            .meterRegistry(new SimpleMeterRegistry())
            .mediaFileRepository(mediaFileRepository)
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(playbackProbeServiceBuilder(probeRepository).build())
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
                    .heldRequests(workerFixture.workerSessions().heldSegmentRequests())
                    .eventPublisher(new CapturingEventPublisher())
                    .build())
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
      if (workerFixture != null) {
        workerFixture.close();
      }
    }
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder().quality(VideoQuality.AUTO).build();
  }

  private ProbeOutcome.Success probe(Path source) {
    var outcome =
        new RemoteFfprobeService(
                workerFixture.workerSessions(),
                workerFixture.sourceNamespaceId(),
                source.getParent())
            .probe(
                ProbeExecutionRequest.builder()
                    .sourcePath(source)
                    .attemptId(UUID.randomUUID())
                    .probeVersion(ProbeVersion.CURRENT)
                    .priority(ProbePriority.BACKGROUND)
                    .build());
    assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
    return (ProbeOutcome.Success) outcome;
  }

  private MediaFile seedMediaFile() {
    return seedMediaFile(testVideo);
  }

  private MediaFile seedMediaFile(Path source) {
    var file =
        MediaFile.builder()
            .filepathUri(FilepathCodec.encode(source))
            .filename(source.getFileName().toString())
            .status(MediaFileStatus.MATCHED)
            .size(source.toFile().length())
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
    assertThat(probe.framerate().orElseThrow()).isPositive();
    assertThat(probe.duration()).isGreaterThan(Duration.ofSeconds(9));
    assertThat(probe.bitrate()).isGreaterThan(0);
  }

  @Test
  @DisplayName("Should choose remux when source codec is compatible")
  void shouldChooseRemuxWhenSourceCodecIsCompatible() {
    var file = seedMediaFile();
    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getHandle().orElseThrow().processId()).isEmpty();
    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName("Should start FFmpeg and produce segments when session is created")
  void shouldStartFfmpegAndProduceSegmentsWhenSessionIsCreated() {
    var file = seedMediaFile();
    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getHandle()).isPresent();
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);

    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> segmentStore.segmentExists(session.getSessionId(), "segment0.m4s"));

    var initialization = segmentStore.readSegment(session.getSessionId(), "init.mp4");
    var segmentData = segmentStore.readSegment(session.getSessionId(), "segment0.m4s");
    assertThat(Fmp4Fixture.firstBoxType(initialization)).isEqualTo("ftyp");
    assertThat(Fmp4Fixture.firstBoxType(segmentData)).isEqualTo("moof");
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
  @DisplayName(
      "Should bound every delivered segment by the advertised bandwidth when a measured copy has"
          + " uneven video complexity")
  void shouldBoundEveryDeliveredSegmentByAdvertisedBandwidthWhenAMeasuredCopyHasUnevenVideo()
      throws Exception {
    var source = generateUnevenVideo();
    probeRepository.setDefaultOutcome(probe(source));
    var file = seedMediaFile(source);
    var session =
        createSession(
            file.getId(),
            UUID.randomUUID(),
            StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build());

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getMediaProbe().streamMeasurements()).isPresent();
    var master = playlistService.generateMultivariantPlaylist(session, "smoke-token");
    var bandwidth = bandwidthAttribute(master, "BANDWIDTH");
    var average = bandwidthAttribute(master, "AVERAGE-BANDWIDTH");
    var media =
        playlistService.generateMediaPlaylist(
            session, StreamSession.defaultVariant(), "smoke-token");
    var durations =
        media
            .lines()
            .filter(line -> line.startsWith("#EXTINF:"))
            .map(line -> Double.parseDouble(line.substring(8, line.indexOf(','))))
            .toList();
    var segments =
        media
            .lines()
            .filter(line -> line.startsWith("segment"))
            .map(line -> line.substring(0, line.indexOf('?')))
            .toList();
    assertThat(segments).hasSize(durations.size()).hasSizeGreaterThan(1);
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> segmentStore.segmentExists(session.getSessionId(), segments.getLast()));

    long highestDeliveredBitrate = 0;
    for (var index = 0; index < segments.size(); index++) {
      var bytes = segmentStore.readSegment(session.getSessionId(), segments.get(index));
      var bitrate = (long) Math.ceil(bytes.length * 8.0 / durations.get(index));
      assertThat(bitrate).as(segments.get(index)).isLessThanOrEqualTo(bandwidth);
      highestDeliveredBitrate = Math.max(highestDeliveredBitrate, bitrate);
    }

    assertThat(highestDeliveredBitrate)
        .as("the dense scene exceeds the old average-times-1.2 estimate")
        .isGreaterThan((long) Math.ceil(average * 1.2));
  }

  private static long bandwidthAttribute(String playlist, String attribute) {
    var matcher = Pattern.compile("[:,]" + attribute + "=(\\d+)").matcher(playlist);
    assertThat(matcher.find()).as(attribute).isTrue();
    return Long.parseLong(matcher.group(1));
  }

  private Path generateUnevenVideo() throws Exception {
    return workerFixture
        .worker()
        .generateMedia(
            testVideo.getParent().resolve("uneven-video.mp4"),
            List.of(
                "-f",
                "lavfi",
                "-i",
                "color=black:size=320x180:rate=24:duration=12",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=320x180:rate=24:duration=12",
                "-f",
                "lavfi",
                "-i",
                "color=black:size=320x180:rate=24:duration=12",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=48000:duration=36",
                "-filter_complex",
                "[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]",
                "-map",
                "[v]",
                "-map",
                "3:a",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-pix_fmt",
                "yuv420p",
                "-g",
                "96",
                "-keyint_min",
                "96",
                "-sc_threshold",
                "0",
                "-c:a",
                "aac",
                "-b:a",
                "128k",
                "-shortest"));
  }

  @Test
  @DisplayName("Should include required HLS tags in media playlist when session is active")
  void shouldIncludeRequiredHlsTagsInMediaPlaylistWhenSessionIsActive() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var playlist =
        playlistService.generateMediaPlaylist(
            session, StreamSession.defaultVariant(), "smoke-token");

    assertThat(playlist)
        .startsWith("#EXTM3U\n")
        .contains("#EXT-X-VERSION:6")
        .contains("#EXT-X-TARGETDURATION:6")
        .contains("#EXT-X-MEDIA-SEQUENCE:0")
        .contains("#EXT-X-PLAYLIST-TYPE:VOD")
        .contains("#EXT-X-MAP:URI=\"init.mp4?t=smoke-token\"")
        .contains("#EXT-X-ENDLIST")
        .doesNotContain("#EXT-X-STREAM-INF");
  }

  @Test
  @DisplayName("Should generate valid segment entries in media playlist when session is active")
  void shouldGenerateValidSegmentEntriesInMediaPlaylistWhenSessionIsActive() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var playlist =
        playlistService.generateMediaPlaylist(
            session, StreamSession.defaultVariant(), "smoke-token");

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
            .filter(l -> l.startsWith("segment") && l.endsWith(".m4s?t=smoke-token"))
            .toList();
    assertThat(segmentLines).hasSizeGreaterThan(1);
    assertThat(segmentLines.getFirst()).isEqualTo("segment0.m4s?t=smoke-token");
  }

  @Test
  @DisplayName(
      "Should produce the first segment at the start position without a job attempt at segment 0"
          + " when the session starts at a later position")
  void
      shouldProduceTheFirstSegmentAtTheStartPositionWithoutAJobAttemptAtSegment0WhenTheSessionStartsAtALaterPosition() {
    var file = seedMediaFile();
    var startPosition = Duration.ofMillis(9_500);

    var session =
        OutcomeTestSupport.accepted(
            streamingService.createSession(
                createStreamSessionCommandBuilder(file.getId(), UUID.randomUUID())
                    .startPosition(Optional.of(startPosition))
                    .build()));
    var sessionId = session.getSessionId();
    var extinfDurations =
        playlistService
            .generateMediaPlaylist(session, StreamSession.defaultVariant(), "smoke-token")
            .lines()
            .filter(line -> line.startsWith("#EXTINF:"))
            .map(line -> new BigDecimal(line.substring("#EXTINF:".length(), line.indexOf(','))))
            .toList();
    var expectedFirstSegment = segmentHolding(extinfDurations, startPosition);
    var handle = session.getHandle().orElseThrow();

    assertThat(expectedFirstSegment).isPositive();
    assertThat(session.getVariantHandles()).hasSize(1);
    assertThat(handle.startSequenceNumber()).isEqualTo(expectedFirstSegment);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(
                      segmentStore.segmentExists(
                          sessionId, SegmentNames.mediaSegmentName(expectedFirstSegment)))
                  .isTrue();
              assertThat(workerFixture.worker().processRunning(handle.attemptId())).isFalse();
            });
    assertThat(segmentStore.segmentExists(sessionId, "segment0.m4s")).isFalse();
  }

  // The segment that a player starts in: the first whose summed EXTINF durations pass the position.
  private static int segmentHolding(List<BigDecimal> extinfDurations, Duration position) {
    var seconds = DecimalSeconds.of(position);
    var end = BigDecimal.ZERO;
    for (int index = 0; index < extinfDurations.size(); index++) {
      end = end.add(extinfDurations.get(index));
      if (seconds.compareTo(end) < 0) {
        return index;
      }
    }

    return extinfDurations.size() - 1;
  }

  @Test
  @DisplayName("Should terminate FFmpeg process when session is destroyed")
  void shouldTerminateFfmpegProcessWhenSessionIsDestroyed() throws Exception {
    Files.createFile(testVideo.getParent().resolve("hold-producer"));
    var file = seedMediaFile();
    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);
    var sessionId = session.getSessionId();

    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> segmentStore.segmentExists(sessionId, "segment0.m4s"));

    var handle = session.getHandle().orElseThrow();
    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(workerFixture.worker().processRunning(handle.attemptId())).isTrue();

    streamingService.destroySession(sessionId);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(workerFixture.worker().processRunning(handle.attemptId())).isFalse());
  }

  @Test
  @DisplayName("Should remove session state when session is destroyed")
  void shouldRemoveSessionStateWhenSessionIsDestroyed() {
    var file = seedMediaFile();
    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);
    var sessionId = session.getSessionId();

    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> segmentStore.segmentExists(sessionId, "segment0.m4s"));

    streamingService.destroySession(sessionId);

    assertThat(streamingService.accessSession(playbackRequest(session))).isEmpty();
  }

  @Test
  @DisplayName("Should not deadlock when FFmpeg produces verbose stderr output")
  void shouldNotDeadlockWhenFfmpegProducesVerboseStderrOutput() throws Exception {
    Files.createFile(testVideo.getParent().resolve("verbose-stderr"));
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var attemptId = session.getHandle().orElseThrow().attemptId();

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(segmentStore.segmentExists(session.getSessionId(), "segment0.m4s"))
                  .isTrue();
              assertThat(segmentStore.segmentExists(session.getSessionId(), "segment1.m4s"))
                  .isTrue();
              assertThat(workerFixture.worker().processRunning(attemptId)).isFalse();
            });
  }

  @Test
  @DisplayName(
      "Should send the named audio track over HLS when the request names a later track of the file")
  void shouldSendTheNamedAudioTrackOverHlsWhenTheRequestNamesALaterTrackOfTheFile()
      throws Exception {
    var source = generateStereoThenMonoAudioTracks();
    probeRepository.setDefaultOutcome(probe(source));
    var file = seedMediaFile(source);

    var session =
        OutcomeTestSupport.accepted(
            streamingService.createSession(
                createStreamSessionCommandBuilder(file.getId(), UUID.randomUUID())
                    .audioTrackPosition(OptionalInt.of(1))
                    .build()));

    assertThat(session.getDelivery()).isEqualTo(StreamDelivery.HLS);
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> segmentStore.segmentExists(session.getSessionId(), "segment0.m4s"));
    var sent =
        Files.write(
            temporaryDirectory.resolve("sent.mp4"),
            Fmp4Fixture.withInitializationSegment(
                segmentStore, session.getSessionId(), "segment0.m4s"));
    assertThat(workerFixture.worker().decodedAudio(source).channelLayout())
        .as("the file's first audio track")
        .isEqualTo("stereo");
    assertThat(workerFixture.worker().decodedAudio(sent).channelLayout())
        .as("the audio track that the stream session sends")
        .isEqualTo("mono");
  }

  // A stereo audio track first and a mono audio track second, beside an H.264 picture that the
  // default declaration copies.
  private Path generateStereoThenMonoAudioTracks() throws Exception {
    return workerFixture
        .worker()
        .generateMedia(
            testVideo.getParent().resolve("stereo-then-mono.mp4"),
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc=size=320x180:rate=24:duration=8",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:duration=8",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=880:duration=8",
                "-map",
                "0:v",
                "-map",
                "1:a",
                "-map",
                "2:a",
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-g",
                "24",
                "-c:a",
                "aac",
                "-ac:a:0",
                "2",
                "-ac:a:1",
                "1"));
  }
}
