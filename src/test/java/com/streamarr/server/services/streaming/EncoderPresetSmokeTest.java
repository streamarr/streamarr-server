package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.EncoderPresetChoice;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeServerSettingsRepository;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("SmokeTest")
@DisplayName("Encoder Preset Smoke Tests")
class EncoderPresetSmokeTest {

  private static final Path TEST_VIDEO =
      Path.of("src/test/resources/BigBuckBunny_320x180_10s.mp4").toAbsolutePath();
  private static final String FIRST_MEDIA_SEGMENT = SegmentNames.mediaSegmentName(0);

  // SVT-AV1 produces repeatable bytes only on one thread, so the worker's encode and each reference
  // encode run SVT-AV1 with lp=1.
  private static final String SVT_AV1_ON_ONE_THREAD =
      """
      encode=()
      previous=
      for value in "$@"; do
        if [[ $previous == -svtav1-params ]]; then
          value="$value:lp=1"
        fi
        encode+=("$value")
        previous=$value
      done
      exec ffmpeg "${encode[@]}"
      """;

  @TempDir private Path temporaryDirectory;
  private Path testVideo;
  private LocalSegmentStore segmentStore;
  private WorkerStreamingSmokeFixture workerFixture;
  private RemoteTranscodeExecutor transcodeExecutor;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private ServerSettingsService serverSettings;
  private ProducerLifecycleService lifecycle;

  @BeforeEach
  void setUp() throws Exception {
    assertThat(TEST_VIDEO).isRegularFile();
    var sourceRoot = Files.createDirectory(temporaryDirectory.resolve("media"));
    testVideo = Files.copy(TEST_VIDEO, sourceRoot.resolve(TEST_VIDEO.getFileName()));
    segmentStore = new LocalSegmentStore(temporaryDirectory.resolve("segments"));
    workerFixture =
        WorkerStreamingSmokeFixture.builder()
            .sourceRoot(sourceRoot)
            .segmentStore(segmentStore)
            .ffmpegScript(SVT_AV1_ON_ONE_THREAD)
            .build();
    workerFixture.start();
    transcodeExecutor =
        new RemoteTranscodeExecutor(
            workerFixture.workerSessions(), workerFixture.sourceNamespaceId(), sourceRoot);
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    serverSettings =
        new ServerSettingsService(
            new FakeAuthorizationService(accountScopedBuilder().build()),
            new FakeServerSettingsRepository());
    lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(
                StreamingProperties.builder()
                    .maxConcurrentTranscodes(3)
                    .targetSegmentDuration(Duration.ofSeconds(6))
                    .sessionTimeout(Duration.ofSeconds(60))
                    .build())
            .runtimeRegistry(runtimeRegistry)
            .sessionMutex(new MutexFactory<>())
            .serverSettings(serverSettings)
            .build();
  }

  @AfterEach
  void tearDown() {
    try {
      if (runtimeRegistry != null) {
        runtimeRegistry.findAll().stream()
            .map(StreamSession::getSessionId)
            .forEach(transcodeExecutor::stop);
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

  // Automatic resolves to faster for libx264. The options are x264's own definitions of faster and
  // medium. medium is also libx264's default.
  @ParameterizedTest(name = "{0}")
  @CsvSource({"AUTOMATIC, ref=2 subme=4 rc_lookahead=20", "MEDIUM, ref=3 subme=7 rc_lookahead=40"})
  @DisplayName("Should reach FFmpeg when an H.264 job carries the server's encoder preset")
  void shouldReachFfmpegWhenH264JobCarriesPreset(
      EncoderPresetChoice choice, String expectedX264Options) throws Exception {
    chooseEncoderPreset(choice);

    var session = startedVideoTranscode("h264");

    assertThat(x264Options(firstMediaSegment(session)))
        .containsAll(List.of(expectedX264Options.split(" ")));
  }

  // Automatic resolves to SVT-AV1 preset 10, and medium is preset 8, which is also SVT-AV1's
  // default. SVT-AV1 writes no preset into its sequence header, so only the media bytes show it.
  @ParameterizedTest(name = "{0}")
  @CsvSource({"AUTOMATIC, 10, 8", "MEDIUM, 8, 10"})
  @DisplayName("Should reach FFmpeg when an AV1 job carries the server's encoder preset")
  void shouldReachFfmpegWhenAv1JobCarriesPreset(
      EncoderPresetChoice choice, int expectedSvtAv1Preset, int otherSvtAv1Preset)
      throws Exception {
    chooseEncoderPreset(choice);

    var session = startedVideoTranscode("av1");

    var produced =
        Fmp4Fixture.withInitializationSegment(
            segmentStore, session.getSessionId(), firstMediaSegment(session));
    var command = workerCommand(session);
    assertThat(firstDifference(svtAv1Reference(command, expectedSvtAv1Preset), produced))
        .as("the first byte that differs from an encode at SVT-AV1 preset %d", expectedSvtAv1Preset)
        .isEqualTo(-1);
    assertThat(firstDifference(svtAv1Reference(command, otherSvtAv1Preset), produced))
        .as("the first byte that differs from an encode at SVT-AV1 preset %d", otherSvtAv1Preset)
        .isNotEqualTo(-1);
  }

  private void chooseEncoderPreset(EncoderPresetChoice choice) {
    var serverAdmin = accountScopedBuilder().build();
    accepted(serverSettings.changeEncoderPreset(serverAdmin, choice.name()));
  }

  private StreamSession startedVideoTranscode(String videoCodecFamily) {
    var session =
        defaultSessionBuilder()
            .sourcePath(testVideo)
            .mediaProbe(
                defaultProbeBuilder()
                    .duration(Duration.ofSeconds(10))
                    .framerate(OptionalDouble.of(24.0))
                    .width(320)
                    .height(180)
                    .bitrate(500_000)
                    .build())
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.VIDEO_TRANSCODE)
                    .videoCodecFamily(videoCodecFamily)
                    .audioDecision(AudioDecision.none())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .build())
            .build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 0);
    return session;
  }

  private byte[] firstMediaSegment(StreamSession session) {
    var sessionId = session.getSessionId();
    await()
        .atMost(60, TimeUnit.SECONDS)
        .until(() -> segmentStore.segmentExists(sessionId, FIRST_MEDIA_SEGMENT));
    return segmentStore.readSegment(sessionId, FIRST_MEDIA_SEGMENT);
  }

  private List<String> workerCommand(StreamSession session) throws Exception {
    return workerFixture
        .worker()
        .commandFor(session.getHandle().orElseThrow().attemptId())
        .orElseThrow();
  }

  // The settings that x264 writes into its unregistered user data SEI, ended by a NUL byte.
  private static List<String> x264Options(byte[] media) {
    var text = new String(media, StandardCharsets.ISO_8859_1);
    var start = text.indexOf("options: ");
    assertThat(start).as("the x264 options in the first media segment").isNotNegative();
    var end = text.indexOf('\0', start);
    return List.of(text.substring(start + "options: ".length(), end).split(" "));
  }

  // Runs the worker's command again with the image's own FFmpeg at the given SVT-AV1 preset,
  // writing to a file instead of the worker's pipe.
  private byte[] svtAv1Reference(List<String> workerCommand, int svtAv1Preset) throws Exception {
    assertThat(workerCommand).as("the worker's FFmpeg arguments").endsWith("pipe:1");
    var arguments = new ArrayList<>(workerCommand.subList(0, workerCommand.size() - 1));
    var preset = arguments.indexOf("-preset");
    if (preset >= 0) {
      arguments.subList(preset, preset + 2).clear();
    }

    var encoder = arguments.indexOf("libsvtav1");
    assertThat(encoder).as("the position of the libsvtav1 encoder").isNotNegative();
    arguments.addAll(encoder + 1, List.of("-preset", String.valueOf(svtAv1Preset)));
    var parameters = arguments.indexOf("-svtav1-params") + 1;
    assertThat(parameters).as("the SVT-AV1 command sets encoder parameters").isPositive();
    arguments.set(parameters, arguments.get(parameters) + ":lp=1");
    var reference = temporaryDirectory.resolve("svtav1-preset-" + svtAv1Preset + ".mp4");
    return Files.readAllBytes(workerFixture.worker().generateMedia(reference, arguments));
  }

  // -1 when the produced bytes open the reference, otherwise the index of the first difference.
  // Produced bytes that run past the end of the reference differ at the reference's length.
  private static int firstDifference(byte[] reference, byte[] produced) {
    var sharedLength = Math.min(reference.length, produced.length);
    var mismatch = Arrays.mismatch(reference, 0, sharedLength, produced, 0, sharedLength);
    if (mismatch != -1 || produced.length <= reference.length) {
      return mismatch;
    }

    return sharedLength;
  }
}
