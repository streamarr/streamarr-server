package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.server.services.streaming.ffmpeg.LocalFfmpegProcessManager;
import com.streamarr.server.services.streaming.ffmpeg.LocalFfprobeService;
import com.streamarr.server.services.streaming.ffmpeg.LocalTranscodeExecutor;
import com.streamarr.server.services.streaming.ffmpeg.TranscodeCapabilityService;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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

  @BeforeAll
  static void checkPrerequisites() {
    assumeTrue(isFfmpegAvailable(), "FFmpeg not found on PATH");
    assumeTrue(Files.exists(TEST_VIDEO), "Test video not found: " + TEST_VIDEO);
  }

  private static boolean isFfmpegAvailable() {
    try {
      var process = new ProcessBuilder("ffmpeg", "-version").start();
      return process.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @BeforeEach
  void setUp() throws IOException {
    segmentBaseDir = Files.createTempDirectory("streamarr-smoke-");
    segmentStore = new LocalSegmentStore(segmentBaseDir);

    var objectMapper = new ObjectMapper();
    var ffprobeService =
        new LocalFfprobeService(
            objectMapper,
            filepath -> {
              try {
                return new ProcessBuilder(
                        "ffprobe",
                        "-v",
                        "quiet",
                        "-print_format",
                        "json",
                        "-show_streams",
                        "-show_format",
                        filepath.toString())
                    .start();
              } catch (IOException e) {
                throw new java.io.UncheckedIOException("Failed to start ffprobe", e);
              }
            });

    var capabilityService =
        new TranscodeCapabilityService(
            command -> new ProcessBuilder(command).redirectErrorStream(false).start());
    capabilityService.detectCapabilities();

    var commandBuilder = new FfmpegCommandBuilder();
    var processManager = new LocalFfmpegProcessManager();
    var transcodeExecutor =
        new LocalTranscodeExecutor(commandBuilder, processManager, segmentStore, capabilityService);

    var decisionService = new TranscodeDecisionService();
    var qualityLadderService = new QualityLadderService();
    var properties = new StreamingProperties(3, 6, 60);

    mediaFileRepository = new FakeMediaFileRepository();
    streamingService =
        new HlsStreamingService(
            mediaFileRepository,
            transcodeExecutor,
            segmentStore,
            ffprobeService,
            decisionService,
            qualityLadderService,
            properties);

    playlistService = new HlsPlaylistService(properties);
  }

  @AfterEach
  void tearDown() {
    try {
      streamingService.getAllSessions().forEach(s -> streamingService.destroySession(s.getSessionId()));
    } catch (Exception e) {
      // best-effort cleanup
    }
    segmentStore.shutdown();
  }

  private MediaFile seedMediaFile() {
    var file =
        MediaFile.builder()
            .filepath(TEST_VIDEO.toString())
            .filename("BigBuckBunny_320x180.mp4")
            .status(MediaFileStatus.MATCHED)
            .size(TEST_VIDEO.toFile().length())
            .build();
    return mediaFileRepository.save(file);
  }

  @Test
  @DisplayName("Should probe test video and return valid media probe")
  void shouldProbeTestVideoAndReturnValidMediaProbe() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = streamingService.createSession(file.getId(), options);

    var probe = session.getMediaProbe();
    assertThat(probe.videoCodec()).isEqualTo("h264");
    assertThat(probe.audioCodec()).isEqualTo("aac");
    assertThat(probe.width()).isEqualTo(320);
    assertThat(probe.height()).isEqualTo(180);
    assertThat(probe.framerate()).isGreaterThan(0);
    assertThat(probe.duration()).isGreaterThan(Duration.ofSeconds(9));
    assertThat(probe.bitrate()).isGreaterThan(0);
  }

  @Test
  @DisplayName("Should choose remux for compatible source")
  void shouldChooseRemuxForCompatibleSource() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = streamingService.createSession(file.getId(), options);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getTranscodeDecision().containerFormat()).isEqualTo(ContainerFormat.MPEGTS);
  }

  @Test
  @DisplayName("Should start FFmpeg and produce segments")
  void shouldStartFfmpegAndProduceSegments() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = streamingService.createSession(file.getId(), options);

    assertThat(session.getHandle()).isNotNull();
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);

    var segmentReady =
        segmentStore.waitForSegment(session.getSessionId(), "segment0.ts", Duration.ofSeconds(30));
    assertThat(segmentReady).isTrue();

    var segmentData = segmentStore.readSegment(session.getSessionId(), "segment0.ts");
    assertThat(segmentData).isNotNull();
    assertThat(segmentData.length).isGreaterThan(0);
    assertThat(segmentData[0]).isEqualTo((byte) 0x47);
  }

  @Test
  @DisplayName("Should generate RFC 8216 compliant master playlist")
  void shouldGenerateRfc8216CompliantMasterPlaylist() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = streamingService.createSession(file.getId(), options);
    var playlist = playlistService.generateMasterPlaylist(session);

    assertThat(playlist).startsWith("#EXTM3U\n");
    assertThat(playlist).contains("#EXT-X-STREAM-INF:");
    assertThat(playlist).contains("BANDWIDTH=");
    assertThat(playlist).contains("RESOLUTION=320x180");
    assertThat(playlist).contains("CODECS=");
    assertThat(playlist).contains("stream.m3u8");
    assertThat(playlist).doesNotContain("\uFEFF");
  }

  @Test
  @DisplayName("Should generate RFC 8216 compliant media playlist")
  void shouldGenerateRfc8216CompliantMediaPlaylist() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = streamingService.createSession(file.getId(), options);
    var playlist = playlistService.generateMediaPlaylist(session);

    assertThat(playlist).startsWith("#EXTM3U\n");
    assertThat(playlist).contains("#EXT-X-VERSION:3");
    assertThat(playlist).contains("#EXT-X-TARGETDURATION:6");
    assertThat(playlist).contains("#EXT-X-MEDIA-SEQUENCE:0");
    assertThat(playlist).contains("#EXT-X-PLAYLIST-TYPE:VOD");
    assertThat(playlist).contains("#EXT-X-ENDLIST");
    assertThat(playlist).doesNotContain("#EXT-X-STREAM-INF");
    assertThat(playlist).doesNotContain("#EXT-X-MAP");

    var extinfLines = playlist.lines().filter(l -> l.startsWith("#EXTINF:")).toList();
    assertThat(extinfLines).isNotEmpty();

    for (var line : extinfLines) {
      var durationStr = line.replace("#EXTINF:", "").replace(",", "");
      var duration = Double.parseDouble(durationStr);
      assertThat(duration).isGreaterThan(0);
      assertThat(Math.round(duration)).isLessThanOrEqualTo(6);
    }

    var segmentLines =
        playlist.lines().filter(l -> l.startsWith("segment") && l.endsWith(".ts")).toList();
    assertThat(segmentLines).hasSizeGreaterThan(1);
    assertThat(segmentLines.getFirst()).isEqualTo("segment0.ts");
  }

  @Test
  @DisplayName("Should shutdown FFmpeg gracefully via stdin quit")
  void shouldShutdownFfmpegGracefullyViaStdinQuit() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = streamingService.createSession(file.getId(), options);
    var sessionId = session.getSessionId();

    segmentStore.waitForSegment(sessionId, "segment0.ts", Duration.ofSeconds(30));

    var handle = session.getHandle();
    assertThat(handle).isNotNull();
    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);

    streamingService.destroySession(sessionId);

    var processHandle = ProcessHandle.of(handle.processId());
    assertThat(processHandle).satisfiesAnyOf(
        ph -> assertThat(ph).isEmpty(),
        ph -> assertThat(ph.get().isAlive()).isFalse());
  }

  @Test
  @DisplayName("Should clean up on session destroy")
  void shouldCleanUpOnSessionDestroy() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = streamingService.createSession(file.getId(), options);
    var sessionId = session.getSessionId();

    segmentStore.waitForSegment(sessionId, "segment0.ts", Duration.ofSeconds(30));

    streamingService.destroySession(sessionId);

    assertThat(streamingService.getSession(sessionId)).isEmpty();
  }
}
