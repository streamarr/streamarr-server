package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.SyntheticHdrSource;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRegistry;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.support.OutcomeTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Builder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Real-worker proof of the H.265 level table and of H.265 Main 10 HDR10 encodes (ADR 0013,
 * streamarr/streamarr-server#58). Each test probes a synthetic source through the worker image and
 * starts an Auto stream session whose encode is libx265. The playlist's codec string must equal the
 * codec string of the initialization segment that the worker produced with the forced level and
 * tier, and the codec string that libx265 writes when it chooses the level and tier on its own with
 * the worker's other settings.
 */
@Tag("SmokeTest")
@DisplayName("HEVC Encode Smoke Tests")
class HevcEncodeSmokeTest {

  private static final int SEGMENT_SECONDS = 6;
  // One short media segment per variant keeps the six 2160p60 libx265 encodes within the default
  // test timeout on a CI runner; libx265 fixes its level and tier before the first frame.
  private static final int SOURCE_SECONDS = 2;
  private static final Duration DELIVERY_LIMIT = Duration.ofMinutes(3);
  // libx265 decides its level and tier when it opens, so a few frames show its own choice.
  private static final String REFERENCE_FRAMES = "8";
  private static final List<String> FORCED_X265_PARAMETERS = List.of("level-idc=", "high-tier=");
  private static final String CODECS_ATTRIBUTE = "CODECS=\"";

  private FakeMediaFileRepository mediaFileRepository;
  private FakeMediaFileContainerInfoRepository probeRepository;
  private LocalSegmentStore segmentStore;
  private HlsStreamingService streamingService;
  private HlsPlaylistService playlistService;
  private SegmentDeliveryCoordinator coordinator;
  private RemoteFfprobeService probes;
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
    var transcodeExecutor =
        new RemoteTranscodeExecutor(
            workerFixture.workerSessions(), workerFixture.sourceNamespaceId(), sourceRoot);
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(Duration.ofSeconds(SEGMENT_SECONDS))
            .sessionTimeout(Duration.ofMinutes(5))
            .producerStallThreshold(Duration.ofMinutes(2))
            .build();
    var sessionRegistry = new InMemoryStreamSessionRegistry();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(sessionRegistry)
            .heldRequests(workerFixture.workerSessions().heldSegmentRequests())
            .build();
    coordinator = rig.coordinator();
    mediaFileRepository = new FakeMediaFileRepository();
    probeRepository = new FakeMediaFileContainerInfoRepository();
    streamingService =
        HlsStreamingService.builder()
            .meterRegistry(new SimpleMeterRegistry())
            .mediaFileRepository(mediaFileRepository)
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(playbackProbeServiceBuilder(probeRepository).build())
            .transcodeDecisionService(
                new TranscodeDecisionService(properties, new SimpleMeterRegistry()))
            .qualityLadderService(new QualityLadderService())
            .properties(properties)
            .authorityGate((_, _) -> true)
            .runtimeRegistry(sessionRegistry)
            .producerLifecycle(rig.lifecycle())
            .deliveryCoordinator(coordinator)
            .languagePreferences(noLanguagePreferences())
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

  @ParameterizedTest(name = "{0}")
  @MethodSource("ladderSources")
  @DisplayName(
      "Should advertise the codec string of each produced initialization segment and of libx265's"
          + " own choice when the server encodes every variant of the ladder to HEVC")
  void shouldAdvertiseTheCodecStringOfEachProducedInitializationSegmentWhenEncodingHevc(
      LadderSource ladderSource) throws Exception {
    var session = sessionFor(ladderSource);
    assertThat(session.getTranscodeDecision().encodeCodecProfile())
        .contains(EncodeCodecProfile.HEVC_MAIN);
    var advertised = advertisedVideoCodecStrings(session);
    assertThat(advertised.keySet())
        .as("the ladder's variants")
        .containsExactlyInAnyOrderElementsOf(ladderSource.expectedVariants());

    for (var variant : session.getVariants()) {
      var produced = Fmp4Fixture.hevcCodecString(producedInitializationSegment(session, variant));
      var ownChoice = Fmp4Fixture.hevcCodecString(libx265OwnChoice(session, variant));

      assertThat(advertised.get(variant.label()))
          .as("the playlist's codec string of %s", variant.label())
          .isEqualTo(produced)
          .isEqualTo(ownChoice);
    }
  }

  // #58: an HEVC encode that keeps PQ forces H.265 Main 10 and writes the source's static HDR
  // metadata. The H.264 source is 10-bit PQ, which the client's H.264 entry cannot play, so the
  // server encodes; an HDR encode wins over H.264 at the same frame rate.
  @Test
  @DisplayName(
      "Should deliver 10-bit PQ with the source's mastering display and the advertised Main 10"
          + " codec string when the server encodes an HDR10 source to HEVC")
  void shouldDeliver10BitPqWithTheSourcesMasteringDisplayWhenTheServerEncodesAnHdr10SourceToHevc()
      throws Exception {
    var session =
        sessionFor(
            writtenHdr10Source(),
            declarationBuilderFor(ProbedCodecFamily.H264)
                .video(
                    List.of(
                        h264EntryBuilder().build(),
                        hevcEntryBuilder()
                            .compatibleBases(
                                List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
                            .build()))
                .build());
    assertThat(session.getTranscodeDecision().encodeCodecProfile())
        .contains(EncodeCodecProfile.HEVC_MAIN_10);
    var variant = session.getVariants().getFirst();
    var initialization = producedInitializationSegment(session, variant);

    assertThat(advertisedVideoCodecStrings(session).get(variant.label()))
        .startsWith("hvc1.2.4.")
        .isEqualTo(Fmp4Fixture.hevcCodecString(initialization))
        .isEqualTo(Fmp4Fixture.hevcCodecString(libx265OwnChoice(session, variant)));
    assertThat(workerFixture.worker().firstVideoFrame(firstMediaSegment(session, variant)))
        .containsEntry("pix_fmt", "yuv420p10le")
        .containsEntry("color_primaries", "bt2020")
        .containsEntry("color_transfer", "smpte2084")
        .containsEntry("color_space", "bt2020nc")
        .containsEntry("red_x", "35400/50000")
        .containsEntry("red_y", "14600/50000")
        .containsEntry("green_x", "8500/50000")
        .containsEntry("green_y", "39850/50000")
        .containsEntry("blue_x", "6550/50000")
        .containsEntry("blue_y", "2300/50000")
        .containsEntry("white_point_x", "15635/50000")
        .containsEntry("white_point_y", "16450/50000")
        .containsEntry("max_luminance", "10000000/10000")
        .containsEntry("min_luminance", "50/10000")
        .containsEntry("max_content", "1000")
        .containsEntry("max_average", "400");
  }

  // Each source opens on a keyframe, so its one segment interval starts on one.
  static List<LadderSource> ladderSources() {
    return List.of(
        LadderSource.builder()
            .name("2160p60")
            .width(3840)
            .height(2160)
            .frameRate("60")
            .expectedVariants(List.of("2160p", "1440p", "1080p", "720p", "480p", "360p"))
            .build(),
        LadderSource.builder()
            .name("2160p23.976")
            .width(3840)
            .height(2160)
            .frameRate("24000/1001")
            .expectedVariants(List.of("2160p", "1440p", "1080p", "720p", "480p", "360p"))
            .build(),
        LadderSource.builder()
            .name("1080p30")
            .width(1920)
            .height(1080)
            .frameRate("30")
            .expectedVariants(List.of("1080p", "720p", "480p", "360p"))
            .build());
  }

  private StreamSession sessionFor(LadderSource ladderSource) throws Exception {
    var source = writtenSource(ladderSource);
    return sessionFor(source, declarationBuilderFor(ProbedCodecFamily.HEVC).build());
  }

  private StreamSession sessionFor(Path source, ClientCapabilityDeclaration declaration)
      throws Exception {
    storeProbe(source);
    var file =
        mediaFileRepository.save(
            MediaFile.builder()
                .filepathUri(FilepathCodec.encode(source))
                .filename(source.getFileName().toString())
                .status(MediaFileStatus.MATCHED)
                .size(Files.size(source))
                .build());
    return OutcomeTestSupport.accepted(
        streamingService.createSession(
            createStreamSessionCommandBuilder(file.getId(), UUID.randomUUID())
                .capabilityDeclaration(declaration)
                .build()));
  }

  // An H.264 source, which a client that declares only HEVC cannot play, so the server encodes.
  private Path writtenSource(LadderSource ladderSource) throws Exception {
    var size = ladderSource.width() + "x" + ladderSource.height();
    return workerFixture
        .worker()
        .generateMedia(
            sourceRoot.resolve(ladderSource.name() + ".mp4"),
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=%s:rate=%s:duration=%d"
                    .formatted(size, ladderSource.frameRate(), SOURCE_SECONDS),
                "-c:v",
                "libx264",
                "-preset",
                "ultrafast",
                "-pix_fmt",
                "yuv420p",
                "-force_key_frames",
                "expr:gte(t,n_forced*2)",
                "-an"));
  }

  // A 10-bit PQ H.264 source with SyntheticHdrSource's mastering display and content light level.
  private Path writtenHdr10Source() throws Exception {
    var display = SyntheticHdrSource.MASTERING_DISPLAY;
    var lightLevel = SyntheticHdrSource.CONTENT_LIGHT_LEVEL;
    var parameters =
        ("colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc"
                + ":mastering-display=G(%d,%d)B(%d,%d)R(%d,%d)WP(%d,%d)L(%d,%d):cll=%d,%d")
            .formatted(
                display.green().x(),
                display.green().y(),
                display.blue().x(),
                display.blue().y(),
                display.red().x(),
                display.red().y(),
                display.whitePoint().x(),
                display.whitePoint().y(),
                display.maxLuminance(),
                display.minLuminance(),
                lightLevel.maxContentLightLevel(),
                lightLevel.maxFrameAverageLightLevel());
    return workerFixture
        .worker()
        .generateMedia(
            sourceRoot.resolve("hdr10.mp4"),
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=640x360:rate=24:duration=%d".formatted(SOURCE_SECONDS),
                "-c:v",
                "libx264",
                "-preset",
                "ultrafast",
                "-pix_fmt",
                "yuv420p10le",
                "-x264-params",
                parameters,
                "-an"));
  }

  // The variant's initialization segment and first media segment as one decodable file.
  private Path firstMediaSegment(StreamSession session, QualityVariant variant) throws Exception {
    var sessionId = session.getSessionId();
    var segment = SegmentNames.storedSegmentName(variant.label(), SegmentNames.mediaSegmentName(0));
    await("the first media segment of " + variant.label())
        .atMost(DELIVERY_LIMIT)
        .until(() -> segmentStore.segmentExists(sessionId, segment));
    var media = new ByteArrayOutputStream();
    media.writeBytes(
        segmentStore.readSegment(
            sessionId,
            SegmentNames.storedSegmentName(
                variant.label(), SegmentNames.INITIALIZATION_SEGMENT_NAME)));
    media.writeBytes(segmentStore.readSegment(sessionId, segment));
    return Files.write(
        temporaryDirectory.resolve("decodable-" + variant.label() + ".mp4"), media.toByteArray());
  }

  private void storeProbe(Path source) {
    var request =
        ProbeExecutionRequest.builder()
            .sourcePath(source)
            .attemptId(UUID.randomUUID())
            .probeVersion(ProbeVersion.CURRENT)
            .priority(ProbePriority.BACKGROUND)
            .build();
    var outcome = probes.probe(request);

    assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
    probeRepository.setDefaultOutcome((ProbeOutcome.Success) outcome);
  }

  // The variant's first media segment starts its job attempt, unless the stream session started it.
  private byte[] producedInitializationSegment(StreamSession session, QualityVariant variant) {
    var sessionId = session.getSessionId();
    var label = variant.label();
    var initialization =
        SegmentNames.storedSegmentName(label, SegmentNames.INITIALIZATION_SEGMENT_NAME);
    coordinator.deliver(sessionId, label, SegmentNames.mediaSegmentName(0));
    await("the initialization segment of " + label)
        .atMost(DELIVERY_LIMIT)
        .until(() -> segmentStore.segmentExists(sessionId, initialization));
    return segmentStore.readSegment(sessionId, initialization);
  }

  // Runs the variant's worker command again with the image's own FFmpeg for a few frames, without
  // the forced level and tier, writing to a file instead of the worker's pipe.
  private byte[] libx265OwnChoice(StreamSession session, QualityVariant variant) throws Exception {
    var attemptId = session.getVariantHandles().get(variant.label()).attemptId();
    var command = workerFixture.worker().commandFor(attemptId).orElseThrow();
    var outputIndex = command.indexOf("pipe:1");
    assertThat(outputIndex).as("the worker's fMP4 output in its FFmpeg arguments").isPositive();
    var arguments = new ArrayList<>(command.subList(0, outputIndex));
    var parametersIndex = arguments.indexOf("-x265-params") + 1;
    assertThat(parametersIndex).as("the libx265 command sets encoder parameters").isPositive();
    var parameters = arguments.get(parametersIndex);
    assertThat(parameters).as("the worker forces the level").contains("level-idc=");
    arguments.set(parametersIndex, withoutForcedLevelAndTier(parameters));
    arguments.addAll(List.of("-frames:v", REFERENCE_FRAMES));
    var reference = temporaryDirectory.resolve("libx265-" + variant.label() + ".mp4");
    return Files.readAllBytes(workerFixture.worker().generateMedia(reference, arguments));
  }

  private static String withoutForcedLevelAndTier(String parameters) {
    return Arrays.stream(parameters.split(":"))
        .filter(parameter -> FORCED_X265_PARAMETERS.stream().noneMatch(parameter::startsWith))
        .collect(Collectors.joining(":"));
  }

  // Each variant's video codec string, the first entry of its CODECS attribute, by its label in
  // playlist order. The URI line after each EXT-X-STREAM-INF starts with the variant's label.
  private Map<String, String> advertisedVideoCodecStrings(StreamSession session) {
    var lines =
        playlistService.generateMultivariantPlaylist(session, "smoke-token").lines().toList();
    var codecs = new LinkedHashMap<String, String>();
    for (var index = 0; index < lines.size() - 1; index++) {
      var line = lines.get(index);
      if (!line.startsWith("#EXT-X-STREAM-INF:")) {
        continue;
      }

      var uri = lines.get(index + 1);
      var start = line.indexOf(CODECS_ATTRIBUTE) + CODECS_ATTRIBUTE.length();
      codecs.put(
          uri.substring(0, uri.indexOf('/')),
          line.substring(start, line.indexOf('"', start)).split(",")[0]);
    }

    return codecs;
  }

  @Builder
  record LadderSource(
      String name, int width, int height, String frameRate, List<String> expectedVariants) {

    @Override
    public String toString() {
      return name;
    }
  }
}
