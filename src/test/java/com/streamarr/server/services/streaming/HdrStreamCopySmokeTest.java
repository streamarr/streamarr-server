package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static com.streamarr.server.fixtures.SyntheticHdrSource.CONTENT_LIGHT_LEVEL;
import static com.streamarr.server.fixtures.SyntheticHdrSource.MASTERING_DISPLAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.DolbyVisionOutput;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MasteringDisplayColorVolume;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fixtures.DolbyVisionConfigurationBox;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.Fmp4Fixture.SeiMessage;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.SyntheticHdrSource;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRegistry;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.support.OutcomeTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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
 * Real-worker proof that an HDR copy delivers what its decision and playlist describe (ADR 0013,
 * ADR 0036). Each test probes a synthetic libx265 source through the pinned worker image, starts a
 * stream session for a client that declares HDR, and reads back the initialization and media
 * segments that the worker's copy delivered.
 */
@Tag("SmokeTest")
@DisplayName("HDR Stream Copy Smoke Tests")
class HdrStreamCopySmokeTest {

  private static final int SEGMENT_SECONDS = 6;
  private static final int REPLACED_SEGMENT = 1;
  // ITU-T H.265 D.2.28 and D.2.35.
  private static final int MASTERING_DISPLAY_PAYLOAD = 137;
  private static final int CONTENT_LIGHT_LEVEL_PAYLOAD = 144;
  private static final int FIRST_NON_PICTURE_TYPE = 32;
  private static final int RPU_TYPE = 62;
  private static final int ENHANCEMENT_LAYER_TYPE = 63;
  private static final int DOLBY_VISION_LEVEL = 1;
  private static final Duration DELIVERY_LIMIT = Duration.ofSeconds(60);

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

  @Test
  @DisplayName(
      "Should write the probed mastering display and content light level each as its own SEI"
          + " message into the hvcC when the server copies an HDR10 source")
  void shouldWriteTheProbedStaticHdrMetadataIntoTheHvccWhenTheServerCopiesAnHdr10Source()
      throws Exception {
    var session = sessionFor(hdr10Source(), hevcEntryBuilder().compatibleBases(hdrBases()).build());

    assertThat(plannedCopy(session))
        .satisfies(
            copy -> {
              assertThat(copy.masteringDisplay()).contains(MASTERING_DISPLAY);
              assertThat(copy.contentLightLevel()).contains(CONTENT_LIGHT_LEVEL);
            });
    assertThat(Fmp4Fixture.hvccSeiMessages(deliveredInitializationSegment(session)))
        .containsExactly(
            List.of(
                new SeiMessage(
                    MASTERING_DISPLAY_PAYLOAD, masteringDisplayPayload(MASTERING_DISPLAY))),
            List.of(
                new SeiMessage(
                    CONTENT_LIGHT_LEVEL_PAYLOAD, contentLightLevelPayload(CONTENT_LIGHT_LEVEL))));
  }

  // ADR 0037: every job attempt of a variant writes the same initialization segment. The MPEG-TS
  // source keeps its parameter sets and SEI messages in its samples, and each keyframe carries its
  // own number, so a record built from an attempt's first sample would differ between attempts.
  @Test
  @DisplayName(
      "Should deliver the same initialization segment from a replacement attempt that starts at a"
          + " later keyframe when the server copies an HDR10 source whose keyframes differ")
  void shouldDeliverTheSameInitializationSegmentFromAReplacementAttemptWhenItCopiesHdr10()
      throws Exception {
    var source =
        SyntheticHdrSource.hevcSourceBuilder()
            .name("hdr10-numbered-keyframes")
            .compatibleBase(CompatibleBase.PQ)
            .staticHdrMetadata(true)
            .numberedKeyframeMessages(true)
            .mpegTsContainer(true)
            .build()
            .writtenBy(workerFixture.worker(), sourceRoot);
    var session = sessionFor(source, hevcEntryBuilder().compatibleBases(hdrBases()).build());
    var fromStart = deliveredInitializationSegment(session);

    var replacement = replacementInitializationSegment(session);

    assertThat(Fmp4Fixture.hvccSeiMessages(fromStart))
        .as("the probed static HDR messages of the record")
        .hasSize(2);
    assertThat(replacement).isEqualTo(fromStart);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("dolbyVisionCopies")
  @DisplayName(
      "Should announce the normalized Dolby Vision record and the sample entry that the copy"
          + " delivers when the client declares the source's profile")
  void shouldAnnounceTheNormalizedRecordAndSampleEntryThatTheCopyDeliversWhenTheClientDeclares(
      DolbyVisionCopy copy) throws Exception {
    var source =
        SyntheticHdrSource.hevcSourceBuilder()
            .name(copy.name())
            .compatibleBase(copy.base())
            .dolbyVisionRecord(Optional.of(copy.sourceRecord()))
            .build()
            .writtenBy(workerFixture.worker(), sourceRoot);
    var session =
        sessionFor(
            source,
            hevcEntryBuilder()
                .compatibleBases(hdrBases())
                .dolbyVisionProfiles(
                    List.of(
                        DolbyVisionProfileCapability.builder()
                            .profile(copy.profile())
                            .maxLevel(DOLBY_VISION_LEVEL)
                            .build()))
                .build());

    assertThat(plannedCopy(session).bitstreamRewrites())
        .containsExactly(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD);
    assertThat(plannedCopy(session).dolbyVisionOutput())
        .contains(new DolbyVisionOutput(copy.profile(), DOLBY_VISION_LEVEL));
    var initialization = deliveredInitializationSegment(session);
    var records = Fmp4Fixture.dolbyVisionRecords(initialization);
    assertThat(records).containsExactly(copy.normalizedRecord());
    var streamInf = streamInf(session);
    assertThat(attribute(streamInf, "CODECS"))
        .contains(Fmp4Fixture.hevcCodecString(initialization));
    assertThat(attribute(streamInf, "SUPPLEMENTAL-CODECS"))
        .contains(supplementalCodecsOf(records.getFirst()));
    assertThat(attribute(streamInf, "VIDEO-RANGE")).contains(copy.base().name());
    assertThat(mediaNalUnitTypes(session, 0))
        .as("one RPU behind each picture, as the source holds them")
        .filteredOn(type -> type == RPU_TYPE)
        .hasSize(pictureCount(mediaNalUnitTypes(session, 0)));
  }

  static Stream<DolbyVisionCopy> dolbyVisionCopies() {
    return Stream.of(
        DolbyVisionCopy.builder()
            .name("profile-8.1")
            .base(CompatibleBase.PQ)
            .profile(DolbyVisionProfile.PROFILE_8_1)
            // An Ultra HD Blu-ray profile 8 record names compatibility 6; an MPEG-TS muxer's
            // reserved bits read as compression 3.
            .sourceRecord(record(6, 3))
            .normalizedRecord(record(1, 0))
            .build(),
        DolbyVisionCopy.builder()
            .name("profile-8.4")
            .base(CompatibleBase.HLG)
            .profile(DolbyVisionProfile.PROFILE_8_4)
            .sourceRecord(record(4, 3))
            .normalizedRecord(record(4, 0))
            .build());
  }

  @Test
  @DisplayName(
      "Should deliver no NAL unit of type 62 or 63 and no Dolby Vision record from the start or"
          + " after a seek when the client declares the base but not the Dolby Vision profile")
  void shouldDeliverNoRpuEnhancementLayerUnitOrRecordWhenTheClientDeclaresOnlyTheBase()
      throws Exception {
    var source =
        SyntheticHdrSource.hevcSourceBuilder()
            .name("profile-8.1-with-enhancement-layer-units")
            .compatibleBase(CompatibleBase.PQ)
            .dolbyVisionRecord(Optional.of(record(1, 0)))
            .enhancementLayerUnits(true)
            .build()
            .writtenBy(workerFixture.worker(), sourceRoot);
    var session = sessionFor(source, hevcEntryBuilder().compatibleBases(hdrBases()).build());

    assertThat(plannedCopy(session).bitstreamRewrites())
        .containsExactly(BitstreamRewrite.STRIP_DOLBY_VISION);
    var fromStart = deliveredInitializationSegment(session);
    var streamInf = streamInf(session);
    assertThat(attribute(streamInf, "CODECS")).contains(Fmp4Fixture.hevcCodecString(fromStart));
    assertThat(attribute(streamInf, "SUPPLEMENTAL-CODECS")).isEmpty();
    assertThat(attribute(streamInf, "VIDEO-RANGE")).contains("PQ");
    assertThat(Fmp4Fixture.dolbyVisionRecords(fromStart)).isEmpty();
    assertThat(IntStream.range(0, mediaSegmentCount(session)).boxed())
        .allSatisfy(segment -> assertWithoutDolbyVisionUnits(session, segment));

    var replacement = replacementInitializationSegment(session);

    assertThat(Fmp4Fixture.dolbyVisionRecords(replacement)).isEmpty();
    assertWithoutDolbyVisionUnits(session, REPLACED_SEGMENT);
  }

  /** A Dolby Vision copy case: the source's record and the record that the copy must deliver. */
  @Builder
  record DolbyVisionCopy(
      String name,
      CompatibleBase base,
      DolbyVisionProfile profile,
      DolbyVisionConfigurationBox sourceRecord,
      DolbyVisionConfigurationBox normalizedRecord) {

    @Override
    public String toString() {
      return "%s on a %s base from compatibility %d and compression %d"
          .formatted(name, base, sourceRecord.compatibilityId(), sourceRecord.compression());
    }
  }

  private static DolbyVisionConfigurationBox record(int compatibilityId, int compression) {
    return DolbyVisionConfigurationBox.builder()
        .profile(8)
        .level(DOLBY_VISION_LEVEL)
        .compatibilityId(compatibilityId)
        .compression(compression)
        .build();
  }

  private Path hdr10Source() throws Exception {
    return SyntheticHdrSource.hevcSourceBuilder()
        .name("hdr10")
        .compatibleBase(CompatibleBase.PQ)
        .staticHdrMetadata(true)
        .build()
        .writtenBy(workerFixture.worker(), sourceRoot);
  }

  private static List<CompatibleBase> hdrBases() {
    return List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG);
  }

  private StreamSession sessionFor(Path source, VideoCapabilityEntry hevcEntry) throws Exception {
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
                .capabilityDeclaration(declaring(hevcEntry))
                .build()));
  }

  private static ClientCapabilityDeclaration declaring(VideoCapabilityEntry hevcEntry) {
    return declarationBuilderFor(ProbedCodecFamily.H264)
        .video(List.of(h264EntryBuilder().build(), hevcEntry))
        .build();
  }

  // An MPEG-TS source has no index, so the probe asks for a packet scan of its keyframes.
  private void storeProbe(Path source) {
    var request =
        ProbeExecutionRequest.builder()
            .sourcePath(source)
            .attemptId(UUID.randomUUID())
            .probeVersion(ProbeVersion.CURRENT)
            .priority(ProbePriority.BACKGROUND)
            .build();
    var outcome = probes.probe(request);
    if (outcome instanceof ProbeOutcome.Success success
        && success.keyframes() instanceof KeyframeKnowledge.PacketScanRequired) {
      outcome = probes.scanKeyframePackets(request);
    }

    assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
    probeRepository.setDefaultOutcome((ProbeOutcome.Success) outcome);
  }

  private static PlannedVideoOutput.StreamCopy plannedCopy(StreamSession session) {
    var planned = session.getTranscodeDecision().plannedVideoOutput();
    assertThat(planned).isInstanceOf(PlannedVideoOutput.StreamCopy.class);
    return (PlannedVideoOutput.StreamCopy) planned;
  }

  // The initialization segment once the copy has delivered every media segment.
  private byte[] deliveredInitializationSegment(StreamSession session) {
    var sessionId = session.getSessionId();
    var lastSegment = SegmentNames.mediaSegmentName(mediaSegmentCount(session) - 1);
    await("the copy's last media segment, " + lastSegment)
        .atMost(DELIVERY_LIMIT)
        .until(() -> segmentStore.segmentExists(sessionId, lastSegment));
    return segmentStore.readSegment(sessionId, SegmentNames.INITIALIZATION_SEGMENT_NAME);
  }

  // Stops the finished attempt, drops its initialization segment and the replaced media segment,
  // and requests that segment, so a replacement attempt seeks to the keyframe that opens it.
  private byte[] replacementInitializationSegment(StreamSession session) throws Exception {
    var sessionId = session.getSessionId();
    workerFixture.worker().killProducer(session.getHandle().orElseThrow().attemptId());
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> !transcodeExecutor.isRunning(sessionId, StreamSession.defaultVariant()));
    var outputDirectory = segmentStore.getOutputDirectory(sessionId);
    Files.delete(outputDirectory.resolve(SegmentNames.INITIALIZATION_SEGMENT_NAME));
    Files.delete(outputDirectory.resolve(SegmentNames.mediaSegmentName(REPLACED_SEGMENT)));

    var delivery =
        coordinator.deliver(
            sessionId,
            StreamSession.defaultVariant(),
            SegmentNames.mediaSegmentName(REPLACED_SEGMENT));

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    await("the replacement attempt's initialization segment")
        .atMost(DELIVERY_LIMIT)
        .until(
            () -> segmentStore.segmentExists(sessionId, SegmentNames.INITIALIZATION_SEGMENT_NAME));
    return segmentStore.readSegment(sessionId, SegmentNames.INITIALIZATION_SEGMENT_NAME);
  }

  private void assertWithoutDolbyVisionUnits(StreamSession session, int segment) {
    assertThat(mediaNalUnitTypes(session, segment))
        .as("the NAL unit types of media segment %d", segment)
        .anyMatch(type -> type < FIRST_NON_PICTURE_TYPE)
        .doesNotContain(RPU_TYPE, ENHANCEMENT_LAYER_TYPE);
  }

  private List<Integer> mediaNalUnitTypes(StreamSession session, int segment) {
    var sessionId = session.getSessionId();
    return Fmp4Fixture.nalUnitTypes(
        segmentStore.readSegment(sessionId, SegmentNames.INITIALIZATION_SEGMENT_NAME),
        segmentStore.readSegment(sessionId, SegmentNames.mediaSegmentName(segment)));
  }

  private static int pictureCount(List<Integer> nalUnitTypes) {
    return (int) nalUnitTypes.stream().filter(type -> type < FIRST_NON_PICTURE_TYPE).count();
  }

  private int mediaSegmentCount(StreamSession session) {
    return (int)
        playlistService
            .generateMediaPlaylist(session, StreamSession.defaultVariant(), "smoke-token")
            .lines()
            .filter(line -> line.startsWith("#EXTINF:"))
            .count();
  }

  private String streamInf(StreamSession session) {
    var streamInfs =
        playlistService
            .generateMultivariantPlaylist(session, "smoke-token")
            .lines()
            .filter(line -> line.startsWith("#EXT-X-STREAM-INF:"))
            .toList();
    assertThat(streamInfs).as("the copy's one variant").hasSize(1);
    return streamInfs.getFirst();
  }

  private static Optional<String> attribute(String streamInf, String name) {
    var matcher = Pattern.compile("[:,]" + name + "=(\"[^\"]*\"|[^,]*)").matcher(streamInf);
    if (!matcher.find()) {
      return Optional.empty();
    }

    return Optional.of(matcher.group(1).replace("\"", ""));
  }

  // The playlist's Dolby Vision codec of the delivered record: the dvh1 sample entry, its profile
  // and level, and the brand of its compatible base, db1p for PQ and db4h for HLG (ADR 0013).
  private static String supplementalCodecsOf(DolbyVisionConfigurationBox record) {
    var brand =
        switch (record.compatibilityId()) {
          case 1 -> "db1p";
          case 4 -> "db4h";
          default -> throw new AssertionError("No brand names compatibility " + record);
        };
    return "dvh1.%02d.%02d/%s".formatted(record.profile(), record.level(), brand);
  }

  // ITU-T H.265 D.2.28: the green, blue and red primaries, the white point, then the maximum and
  // minimum luminances.
  private static String masteringDisplayPayload(MasteringDisplayColorVolume display) {
    var payload = ByteBuffer.allocate(24);
    for (var coordinates :
        List.of(display.green(), display.blue(), display.red(), display.whitePoint())) {
      payload.putShort((short) coordinates.x()).putShort((short) coordinates.y());
    }

    payload.putInt((int) display.maxLuminance()).putInt((int) display.minLuminance());
    return HexFormat.of().withUpperCase().formatHex(payload.array());
  }

  // ITU-T H.265 D.2.35: MaxCLL, then MaxFALL.
  private static String contentLightLevelPayload(ContentLightLevel level) {
    var payload =
        ByteBuffer.allocate(4)
            .putShort((short) level.maxContentLightLevel())
            .putShort((short) level.maxFrameAverageLightLevel());
    return HexFormat.of().withUpperCase().formatHex(payload.array());
  }
}
