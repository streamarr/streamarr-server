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
  private static final int SVT_AV1_DEFAULT_PRESET = 8;

  // SVT-AV1 produces repeatable bytes only on one thread. The worker's encode gets this suffix from
  // SVT_AV1_ON_ONE_THREAD, and each reference encode gets it in svtAv1Reference, because
  // generateMedia runs the image's FFmpeg without the worker's script.
  private static final String ONE_SVT_AV1_THREAD = ":lp=1";
  private static final String SVT_AV1_ON_ONE_THREAD =
      """
      encode=()
      previous=
      for value in "$@"; do
        if [[ $previous == -svtav1-params ]]; then
          value="$value%s"
        fi
        encode+=("$value")
        previous=$value
      done
      exec ffmpeg "${encode[@]}"
      """
          .formatted(ONE_SVT_AV1_THREAD);

  @TempDir private Path temporaryDirectory;
  private Path testVideo;
  private LocalSegmentStore segmentStore;
  private WorkerStreamingSmokeFixture workerFixture;
  private RemoteTranscodeExecutor transcodeExecutor;
  private final FakeRuntimeStreamSessionRegistry runtimeRegistry =
      new FakeRuntimeStreamSessionRegistry();
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
    if (workerFixture == null) {
      return;
    }

    workerFixture.stopStreamSessionsAndClose(
        runtimeRegistry.findAll().stream().map(StreamSession::getSessionId).toList());
  }

  // Automatic resolves to faster for libx264. The options are x264's own definitions of faster and
  // veryfast. Neither is libx264's default, medium (ref=3 subme=7 rc_lookahead=40), so a worker
  // that passes no preset fails both rows.
  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "AUTOMATIC, ref=2 subme=4 rc_lookahead=20",
    "VERYFAST, ref=1 subme=2 rc_lookahead=10"
  })
  @DisplayName("Should reach FFmpeg when an H.264 job carries the server's encoder preset")
  void shouldReachFfmpegWhenH264JobCarriesPreset(
      EncoderPresetChoice choice, String expectedX264Options) throws Exception {
    chooseEncoderPreset(choice);

    var session = startedVideoTranscode("h264");

    assertThat(x264Options(firstMediaSegment(session)))
        .containsAll(List.of(expectedX264Options.split(" ")));
  }

  // Automatic resolves to SVT-AV1 preset 10, and veryfast is preset 11. SVT-AV1 writes no preset
  // into its sequence header, so the encoded packets show it. The worker rebuilds the MP4
  // fragments, so compare packet hashes independently of their container headers. The packets must
  // also differ from an encode at SVT-AV1's default preset, which a worker that passes no preset
  // produces.
  @ParameterizedTest(name = "{0}")
  @CsvSource({"AUTOMATIC, 10", "VERYFAST, 11"})
  @DisplayName("Should reach FFmpeg when an AV1 job carries the server's encoder preset")
  void shouldReachFfmpegWhenAv1JobCarriesPreset(
      EncoderPresetChoice choice, int expectedSvtAv1Preset) throws Exception {
    chooseEncoderPreset(choice);

    var session = startedVideoTranscode("av1");

    var producedMedia =
        Files.write(
            temporaryDirectory.resolve("produced.mp4"),
            Fmp4Fixture.withInitializationSegment(
                segmentStore, session.getSessionId(), firstMediaSegment(session)));
    var produced = workerFixture.worker().videoPacketHashes(producedMedia);
    var command = workerCommand(session);
    assertThat(produced).as("the served segment's encoded video packets").isNotEmpty();
    assertThat(svtAv1Reference(command, expectedSvtAv1Preset))
        .as("the encoded video packets at SVT-AV1 preset %d", expectedSvtAv1Preset)
        .startsWith(produced.toArray(String[]::new));
    var defaultPreset = svtAv1Reference(command, SVT_AV1_DEFAULT_PRESET);
    assertThat(defaultPreset).hasSizeGreaterThanOrEqualTo(produced.size());
    assertThat(produced)
        .as("the encoded video packets differ from SVT-AV1's default preset")
        .isNotEqualTo(defaultPreset.subList(0, produced.size()));
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
    lifecycle.startFirstListedVariant(session);
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
    assertThat(end).as("the end of the x264 options").isGreaterThan(start);
    return List.of(text.substring(start + "options: ".length(), end).split(" "));
  }

  // Runs the worker's command again with the image's own FFmpeg at the given SVT-AV1 preset,
  // writing to a file instead of the worker's pipe.
  private List<String> svtAv1Reference(List<String> workerCommand, int svtAv1Preset)
      throws Exception {
    assertThat(workerCommand).as("the worker's FFmpeg arguments").endsWith("pipe:1");
    var arguments = new ArrayList<>(workerCommand.subList(0, workerCommand.size() - 1));
    var presetFlagIndex = arguments.indexOf("-preset");
    if (presetFlagIndex >= 0) {
      arguments.subList(presetFlagIndex, presetFlagIndex + 2).clear();
    }

    var encoderIndex = arguments.indexOf("libsvtav1");
    assertThat(encoderIndex).as("the position of the libsvtav1 encoder").isNotNegative();
    arguments.addAll(encoderIndex + 1, List.of("-preset", String.valueOf(svtAv1Preset)));
    var svtAv1ParametersIndex = arguments.indexOf("-svtav1-params") + 1;
    assertThat(svtAv1ParametersIndex)
        .as("the SVT-AV1 command sets encoder parameters")
        .isPositive();
    arguments.set(svtAv1ParametersIndex, arguments.get(svtAv1ParametersIndex) + ONE_SVT_AV1_THREAD);
    var reference = temporaryDirectory.resolve("svtav1-preset-" + svtAv1Preset + ".mp4");
    return workerFixture
        .worker()
        .videoPacketHashes(workerFixture.worker().generateMedia(reference, arguments));
  }
}
