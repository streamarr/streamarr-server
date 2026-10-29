package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.MediaFileProbePropertyIssue;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TransferCharacteristics;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.fixtures.DolbyVisionRpuFixture;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The merge gate of streamarr/streamarr-server#394. It probes, decides and encodes through the
 * server path, against the worker image that {@code worker-image.env} pins. The FATE samples state
 * no license, so the gate reads them from the local copy of FFmpeg's FATE suite that the {@code
 * FATE_SUITE} environment variable or system property names, and builds every derived file at test
 * time in a temporary directory. Without {@code FATE_SUITE}, the gate fails.
 */
@Tag("SmokeTest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Probe Version 5 Merge Gate Smoke Tests")
class ProbeVersion5MergeGateSmokeTest {

  private static final String FATE_SUITE = "FATE_SUITE";
  private static final String CONTAINER_MEDIA_ROOT = "/media/";
  private static final String PROFILE_5_SAMPLE = "mov/dovi-p5.mp4";
  private static final int SEGMENT_SECONDS = 6;
  private static final int MEASURED_FRAMES = 3;
  // FATE's profile 5 RPU reshapes every picture to neutral chroma, a mean Cr of 128 on the 8-bit
  // scale. A solid red picture that keeps its own chroma has a mean Cr near 240, so an encode that
  // applies no RPU lies far from one that applies each RPU. Two encodes that apply the same RPUs to
  // the same pictures differ only by rounding.
  private static final String RED_PICTURES = "color=c=red:size=640x360:rate=24:duration=2";
  private static final double CR_TOLERANCE = 0.5;
  private static final double LOST_RESHAPING_CR_DISTANCE = 20;
  private static final Duration KEYFRAME_PACKET_SCAN_WAIT = Duration.ofSeconds(90);

  private final StreamingProperties properties =
      StreamingProperties.builder()
          .targetSegmentDuration(Duration.ofSeconds(SEGMENT_SECONDS))
          .build();
  private final TranscodeDecisionService decisions =
      new TranscodeDecisionService(properties, new SimpleMeterRegistry());
  private final List<UUID> streamSessionIds = new ArrayList<>();

  private Path temporaryDirectory;
  private Path fateSuite;
  private Path mediaRoot;
  private LocalSegmentStore segmentStore;
  private WorkerStreamingSmokeFixture workerFixture;
  private RemoteFfprobeService probes;
  private RemoteTranscodeExecutor transcodeExecutor;

  @BeforeAll
  void startWorker(@TempDir Path classDirectory) throws Exception {
    fateSuite = fateSuite();
    temporaryDirectory = classDirectory;
    mediaRoot = Files.createDirectory(temporaryDirectory.resolve("media"));
    segmentStore =
        new LocalSegmentStore(Files.createDirectory(temporaryDirectory.resolve("segments")));
    workerFixture =
        WorkerStreamingSmokeFixture.builder()
            .sourceRoot(mediaRoot)
            .segmentStore(segmentStore)
            .build();
    workerFixture.start();
    probes =
        new RemoteFfprobeService(
            workerFixture.workerSessions(), workerFixture.sourceNamespaceId(), mediaRoot);
    transcodeExecutor =
        new RemoteTranscodeExecutor(
            workerFixture.workerSessions(), workerFixture.sourceNamespaceId(), mediaRoot);
  }

  @AfterAll
  void stopWorker() {
    if (workerFixture != null) {
      workerFixture.stopStreamSessionsAndClose(streamSessionIds);
    }
  }

  // ADR 0036: HDR10 is a PQ base with static HDR metadata, and PQ10 is a PQ base without it. A PQ
  // stream is PQ10 only when the probe read its first frames, found neither static value, and
  // omitted neither. FATE's sample declares a mastering display in its Matroska mastering elements
  // and in an SEI message of its hvcC record, and a light level only in the Matroska elements.
  // MPEG-TS carries no static values of its own, so the copy keeps the mastering display only in
  // the SEI message. The PQ10 file holds the same pictures without their SEI NAL units.
  @Test
  @DisplayName(
      "Should tell an HDR10 MPEG-TS file from a PQ10 file when only SEI messages carry the HDR10"
          + " file's static values")
  void shouldTellAnHdr10MpegTsFileFromAPq10FileWhenOnlySeiMessagesCarryTheHdr10FilesStaticValues()
      throws Exception {
    var source = copyFromFateSuite("mkv/hdr10tags-both.mkv");
    var hdr10 =
        derive(
            "hdr10-sei-only.ts",
            List.of("-i", inContainer(source), "-map", "0:v", "-c", "copy", "-f", "mpegts"));
    var pq10 =
        derive(
            "pq10.ts",
            List.of(
                "-i",
                inContainer(hdr10),
                "-map",
                "0:v",
                "-c",
                "copy",
                "-bsf:v",
                "filter_units=remove_types=39|40",
                "-f",
                "mpegts"));

    assertThat(worker().videoStreamSideDataTypes(hdr10))
        .as("the side data that the HDR10 file's container declares")
        .isEmpty();
    assertThat(worker().videoStreamSideDataTypes(pq10))
        .as("the side data that the PQ10 file's container declares")
        .isEmpty();
    var hdr10Video = chosenVideo(probe(hdr10));
    var pq10Video = chosenVideo(probe(pq10));

    assertThat(hdr10Video.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .contains(CompatibleBase.PQ);
    assertThat(hdr10Video.masteringDisplay()).as("the HDR10 mastering display").isPresent();
    assertThat(hdr10Video.videoProperties())
        .map(VideoStreamProperties::firstFramesRead)
        .contains(true);
    assertThat(pq10Video.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .contains(CompatibleBase.PQ);
    assertThat(pq10Video.masteringDisplay()).as("the PQ10 mastering display").isEmpty();
    assertThat(pq10Video.contentLightLevel()).as("the PQ10 light level").isEmpty();
    assertThat(pq10Video.videoProperties())
        .hasValueSatisfying(
            facts -> {
              assertThat(facts.firstFramesRead()).as("first frames read").isTrue();
              assertThat(facts.masteringDisplayOmitted()).as("mastering display omitted").isFalse();
              assertThat(facts.contentLightLevelOmitted()).as("light level omitted").isFalse();
            });
  }

  // FATE's suite names its HDR10+ HEVC sample hdr10_plus_h265_sample.hevc. It holds no file named
  // hdr10-plus.hevc.
  @Test
  @DisplayName("Should report HDR10+ present when the worker probes FATE's HDR10+ HEVC sample")
  void shouldReportHdr10PlusPresentWhenTheWorkerProbesFatesHdr10PlusHevcSample() throws Exception {
    var video = chosenVideo(probe(copyFromFateSuite("hevc/hdr10_plus_h265_sample.hevc")));

    assertThat(video.dynamicRange()).map(VideoDynamicRange::hdr10Plus).contains(Presence.PRESENT);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        PROFILE_5_SAMPLE,
        "mov/dovi-p7-hvce.mp4",
        "mov/dovi-p81.mp4",
        "mkv/dovi-p7-hvce.mkv"
      })
  @DisplayName("Should report an observed RPU when the worker probes a FATE Dolby Vision sample")
  void shouldReportAnObservedRpuWhenTheWorkerProbesAFateDolbyVisionSample(String fateSample)
      throws Exception {
    var video = chosenVideo(probe(copyFromFateSuite(fateSample)));

    assertThat(dolbyVision(video).observedRpu()).isEqualTo(Presence.PRESENT);
  }

  // These samples keep the base layer in stream 0 and the enhancement layer, its configuration
  // record and every RPU in stream 1. The chosen video stream is the base layer, and probe version
  // 5 reads the frames of the chosen video stream only (#394, "Not in this issue"). So the probe
  // finds no RPU in the base layer, and it cannot report the enhancement layer's RPUs.
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"mov/dovi-p7.mp4", "mpegts/dovi-p7.ts"})
  @DisplayName(
      "Should report the base layer's Dolby Vision absent when the enhancement layer's stream"
          + " carries the RPUs")
  void shouldReportTheBaseLayersDolbyVisionAbsentWhenTheEnhancementLayersStreamCarriesTheRpus(
      String fateSample) throws Exception {
    var outcome = probe(copyFromFateSuite(fateSample));
    var baseLayer = chosenVideo(outcome);
    var enhancementLayer =
        outcome.streams().stream().filter(stream -> stream.index() == 1).findFirst().orElseThrow();

    assertThat(baseLayer.index()).as("the chosen video stream").isZero();
    assertThat(baseLayer.dynamicRange())
        .map(VideoDynamicRange::dolbyVision)
        .containsInstanceOf(DolbyVision.Absent.class);
    assertThat(dolbyVision(enhancementLayer).declared())
        .hasValueSatisfying(
            declared -> {
              assertThat(declared.profile()).hasValue(7);
              assertThat(declared.enhancementLayer()).as("an enhancement layer").isTrue();
            });
    assertThat(dolbyVision(enhancementLayer).observedRpu()).isEqualTo(Presence.UNSPECIFIED);
  }

  // #394: probe version 4 gave a profile 5 stream without a configuration record the compatible
  // base of its signaled transfer, so the interim copy rule copied the stream, and the copy played
  // teal. Probe version 5 finds profile 5 in the first RPU header, which leaves the stream no
  // compatible base. The same pictures without their RPUs are copied, so only the RPUs rule the
  // copy out. The pinned worker answers probe version 5 only, so no version 4 outcome can show it.
  // FATE's black pictures keep a mean Cr of 128 with or without tone mapping, so the gate reads the
  // tone mapping from the worker's command and the SDR result from the encode's color tags.
  @ParameterizedTest(name = "{0}")
  @MethodSource("recordLessProfile5Files")
  @DisplayName(
      "Should tone-map record-less Dolby Vision profile 5 to SDR when probe version 5 finds the"
          + " profile in its first RPU header")
  void shouldToneMapRecordLessProfile5ToSdrWhenProbeVersion5FindsTheProfileInItsFirstRpuHeader(
      String fileName, Optional<TransferCharacteristics> signaledTransfer) throws Exception {
    var file = recordLessProfile5(fileName);
    var withoutRpus =
        derive(
            "without-rpus-" + fileName,
            List.of(
                "-i",
                inContainer(file),
                "-map",
                "0:v",
                "-c",
                "copy",
                "-bsf:v",
                "dovi_rpu=strip=1"));

    assertThat(worker().videoStreamSideDataTypes(file))
        .as("the container's side data")
        .doesNotContain("DOVI configuration record");
    var outcome = probeWithKeyframes(file);
    var video = chosenVideo(outcome);

    assertThat(video.videoProperties())
        .map(VideoStreamProperties::transferCharacteristics)
        .contains(signaledTransfer);
    assertThat(video.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .contains(CompatibleBase.NONE);
    assertThat(dolbyVision(video).declared()).as("the configuration record").isEmpty();
    assertThat(dolbyVision(video).observedRpu()).isEqualTo(Presence.PRESENT);
    assertThat(dolbyVision(video).firstRpuHeader())
        .hasValueSatisfying(
            header -> {
              assertThat(header.vdrRpuProfile()).isZero();
              assertThat(header.baseLayerFullRange()).isTrue();
            });
    assertThat(decide(probeWithKeyframes(withoutRpus)).transcodeMode().copiesVideo())
        .as("a copy of the same pictures without RPUs")
        .isTrue();

    var encode = encode(file, outcome);

    assertThat(worker().commandFor(encode.attemptId()).orElseThrow())
        .as("the worker's FFmpeg arguments")
        .anySatisfy(argument -> assertThat(argument).contains("tonemapx="));
    assertThat(worker().decodedVideoFrameCount(encode.media())).as("decoded frames").isPositive();
    assertThat(worker().videoStreamEntry(encode.media(), "codec_name")).isEqualTo("h264");
    assertThat(worker().videoStreamEntry(encode.media(), "color_transfer")).isEqualTo("bt709");
    assertThat(worker().videoStreamEntry(encode.media(), "color_primaries")).isEqualTo("bt709");
  }

  // FATE's mov/dovi-p5.mp4 decodes to black pictures with neutral chroma, so every encode of it
  // has a mean Cr of 128 whether or not it applies the RPUs. The gate therefore puts the sample's
  // profile 5 RPU after each synthesized red picture, and moves each RPU into the next picture's
  // sample for the shifted file. FFmpeg's HEVC decoder applies an RPU only as the last NAL unit of
  // its sample, so one FFmpeg encodes the shifted file without reshaping its pictures. The
  // worker's command for the conforming file, run on the shifted file with the image's FFmpeg,
  // shows that loss. The server's encode of the shifted file runs the worker's RPU repair.
  @Test
  @DisplayName(
      "Should keep the conforming encode's colors when the worker repairs a shifted profile 5"
          + " file")
  void shouldKeepTheConformingEncodesColorsWhenTheWorkerRepairsAShiftedProfile5File()
      throws Exception {
    var conforming = profile5RedPictures();
    var shifted =
        writeOnce(
            "dovi-p5-red-shifted.mp4",
            DolbyVisionRpuFixture.shiftEachRpuIntoTheNextSample(Files.readAllBytes(conforming)));
    var conformingOutcome = probe(conforming);
    var shiftedOutcome = probe(shifted);
    var shiftedVideo = chosenVideo(shiftedOutcome);

    assertThat(dolbyVision(chosenVideo(conformingOutcome)).rpuPlacement())
        .isEqualTo(DolbyVisionRpuPlacement.AFTER_OWN_PICTURE);
    assertThat(dolbyVision(shiftedVideo).observedRpu()).isEqualTo(Presence.PRESENT);
    assertThat(dolbyVision(shiftedVideo).rpuPlacement())
        .isEqualTo(DolbyVisionRpuPlacement.STARTS_SAMPLE);
    assertThat(shiftedVideo.dynamicRange())
        .map(MediaFileProbePropertyIssue::foundIn)
        .hasValueSatisfying(
            issues ->
                assertThat(issues)
                    .containsExactly(MediaFileProbePropertyIssue.DOLBY_VISION_RPU_STARTS_SAMPLE));
    assertThat(shiftedVideo.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .contains(CompatibleBase.NONE);
    assertThat(shiftedVideo.videoProperties())
        .flatMap(VideoStreamProperties::firstPacketsCarryPresentationTimes)
        .contains(true);

    var conformingEncode = encode(conforming, conformingOutcome);
    var repairedEncode = encode(shifted, shiftedOutcome);
    var conformingCr = worker().meanCr(conformingEncode.media(), MEASURED_FRAMES);
    var repairedCr = worker().meanCr(repairedEncode.media(), MEASURED_FRAMES);
    var unrepairedCr = worker().meanCr(withoutRepair(conformingEncode, shifted), MEASURED_FRAMES);

    assertThat(Math.abs(unrepairedCr - conformingCr))
        .as(
            "the distance of one FFmpeg's encode of the shifted file (Cr %.2f) from the conforming"
                + " encode (Cr %.2f)",
            unrepairedCr, conformingCr)
        .isGreaterThan(LOST_RESHAPING_CR_DISTANCE);
    assertThat(repairedCr)
        .as("the repaired encode's mean Cr against the conforming encode's %.2f", conformingCr)
        .isCloseTo(conformingCr, within(CR_TOLERANCE));
  }

  static Stream<Arguments> recordLessProfile5Files() {
    return Stream.of(
        Arguments.of("dovi-p5-no-record-bt709.mp4", Optional.of(TransferCharacteristics.BT709)),
        Arguments.of("dovi-p5-no-descriptor.ts", Optional.empty()));
  }

  // FFmpeg's MPEG-TS muxer writes the Dolby Vision descriptor only under -strict unofficial, and
  // its MP4 muxer writes a configuration record only from the source's record, so an MP4 written
  // from the descriptor-less copy has no record either.
  private Path recordLessProfile5(String fileName) throws Exception {
    var withoutDescriptor =
        derive(
            "dovi-p5-no-descriptor.ts",
            List.of(
                "-i",
                inContainer(copyFromFateSuite(PROFILE_5_SAMPLE)),
                "-map",
                "0:v",
                "-c",
                "copy",
                "-f",
                "mpegts"));
    return switch (fileName) {
      case "dovi-p5-no-descriptor.ts" -> withoutDescriptor;
      case "dovi-p5-no-record-bt709.mp4" ->
          derive(
              fileName,
              List.of(
                  "-i",
                  inContainer(withoutDescriptor),
                  "-map",
                  "0:v",
                  "-c",
                  "copy",
                  "-bsf:v",
                  "hevc_metadata=colour_primaries=1:transfer_characteristics=1"
                      + ":matrix_coefficients=1",
                  "-tag:v",
                  "hvc1",
                  "-f",
                  "mp4"));
      default -> throw new IllegalArgumentException("No record-less profile 5 file " + fileName);
    };
  }

  // x265 writes no B-frames here, so the raw stream's decode order is its presentation order, and
  // FFmpeg stamps each copied packet with its presentation time.
  private Path profile5RedPictures() throws Exception {
    var fateRpus =
        derive(
            "dovi-p5.hevc",
            List.of(
                "-i",
                inContainer(copyFromFateSuite(PROFILE_5_SAMPLE)),
                "-map",
                "0:v",
                "-c",
                "copy",
                "-f",
                "hevc"));
    var redPictures =
        derive(
            "red.hevc",
            List.of(
                "-f",
                "lavfi",
                "-i",
                RED_PICTURES,
                "-pix_fmt",
                "yuv420p10le",
                "-c:v",
                "libx265",
                "-preset",
                "ultrafast",
                "-x265-params",
                "log-level=none:bframes=0",
                "-f",
                "hevc"));
    var withRpus =
        writeOnce(
            "dovi-p5-red.hevc",
            DolbyVisionRpuFixture.withRpuAfterEachPicture(
                Files.readAllBytes(redPictures),
                DolbyVisionRpuFixture.firstRpu(Files.readAllBytes(fateRpus))));
    return derive(
        "dovi-p5-red.mp4",
        List.of(
            "-f",
            "hevc",
            "-framerate",
            "24",
            "-i",
            inContainer(withRpus),
            "-c",
            "copy",
            "-f",
            "mp4"));
  }

  private record Encode(Path source, Path media, UUID attemptId) {}

  private Encode encode(Path source, ProbeOutcome.Success outcome) throws Exception {
    var probe = outcome.mediaProbe();
    var decision = decide(outcome);
    assertThat(decision.transcodeMode().copiesVideo())
        .as("a copy of %s", source.getFileName())
        .isFalse();
    var sessionId = UUID.randomUUID();
    streamSessionIds.add(sessionId);
    var handle =
        transcodeExecutor.start(
            TranscodeRequest.builder()
                .sessionId(sessionId)
                .sourcePath(source)
                .targetSegmentDuration(SEGMENT_SECONDS)
                .framerate(probe.framerate())
                .width(probe.displayedSize().width())
                .height(probe.displayedSize().height())
                .bitrate(4_000_000)
                .encoderPreset(EncoderPreset.FASTER)
                .transcodeDecision(decision)
                .mediaSegmentCount(
                    new MediaSegmentTimeline.PlainGrid(
                            probe.duration(), Duration.ofSeconds(SEGMENT_SECONDS))
                        .mediaSegmentCount())
                .build());
    var firstSegment = SegmentNames.mediaSegmentName(0);
    try {
      await()
          .atMost(Duration.ofSeconds(60))
          .until(
              () ->
                  segmentStore.segmentExists(sessionId, SegmentNames.INITIALIZATION_SEGMENT_NAME)
                      && segmentStore.segmentExists(sessionId, firstSegment));
      var media =
          Files.write(
              temporaryDirectory.resolve("encoded-" + source.getFileName()),
              Fmp4Fixture.withInitializationSegment(segmentStore, sessionId, firstSegment));
      return new Encode(source, media, handle.attemptId());
    } finally {
      transcodeExecutor.stop(sessionId);
    }
  }

  // Runs the worker's one-FFmpeg command for the conforming encode on the shifted file, with the
  // image's FFmpeg, writing to a file instead of the worker's pipe.
  private Path withoutRepair(Encode conformingEncode, Path shifted) throws Exception {
    var command = worker().commandFor(conformingEncode.attemptId()).orElseThrow();
    assertThat(command).as("the worker's FFmpeg arguments").endsWith("pipe:1");
    var arguments = new ArrayList<>(command.subList(0, command.size() - 1));
    var input = arguments.indexOf(inContainer(conformingEncode.source()));
    assertThat(input).as("the position of the conforming source").isNotNegative();
    arguments.set(input, inContainer(shifted));
    return worker()
        .generateMedia(temporaryDirectory.resolve("unrepaired-dovi-p5-shifted.mp4"), arguments);
  }

  private TranscodeDecision decide(ProbeOutcome.Success outcome) {
    return decisions
        .decide(
            TranscodeDecisionRequest.builder()
                .source(outcome.mediaProbe())
                .clientOptions(
                    StreamingOptions.builder().supportedCodecs(List.of("h264", "hevc")).build())
                .encodableVideoCodecFamilies(transcodeExecutor.encodableVideoCodecFamilies())
                .build())
        .orElseThrow();
  }

  private ProbeOutcome.Success probe(Path source) {
    return success(source, probes.probe(probeRequest(source)));
  }

  // An MPEG-TS source has no index, so its probe asks for a keyframe packet scan, which the server
  // runs before it decides. A job attempt closes the worker's keyframe packet scans until 30 s
  // after the last attempt ends, and the server defers a scan that no worker takes, so the gate
  // waits for one as the server's durable scan does.
  private ProbeOutcome.Success probeWithKeyframes(Path source) {
    var probed = probe(source);
    if (!probed.needsKeyframePacketScan()) {
      return probed;
    }

    var request = probeRequest(source);
    var scanned =
        await()
            .atMost(KEYFRAME_PACKET_SCAN_WAIT)
            .pollInterval(Duration.ofSeconds(2))
            .ignoreException(ProbeWorkersBusyException.class)
            .until(() -> probes.scanKeyframePackets(request), Objects::nonNull);
    return success(source, scanned);
  }

  private static ProbeExecutionRequest probeRequest(Path source) {
    return ProbeExecutionRequest.builder()
        .sourcePath(source)
        .attemptId(UUID.randomUUID())
        .probeVersion(ProbeVersion.CURRENT)
        .priority(ProbePriority.BACKGROUND)
        .build();
  }

  private static ProbeOutcome.Success success(Path source, ProbeOutcome outcome) {
    assertThat(outcome)
        .as("the probe of %s", source.getFileName())
        .isInstanceOf(ProbeOutcome.Success.class);
    return (ProbeOutcome.Success) outcome;
  }

  private static StreamInfo chosenVideo(ProbeOutcome.Success outcome) {
    var index = outcome.chosenVideoStream().orElseThrow().streamIndex();
    return outcome.streams().stream()
        .filter(stream -> stream.index() == index)
        .findFirst()
        .orElseThrow();
  }

  private static DolbyVision.Present dolbyVision(StreamInfo video) {
    var dolbyVision = video.dynamicRange().orElseThrow().dolbyVision();
    assertThat(dolbyVision)
        .as("the Dolby Vision facts of stream %d", video.index())
        .isInstanceOf(DolbyVision.Present.class);
    return (DolbyVision.Present) dolbyVision;
  }

  // Docker Desktop's file share can serve a replaced file's old directory entry for a moment, and
  // the worker then finds no file under that name. So the gate writes each media file once.
  private Path copyFromFateSuite(String sample) throws Exception {
    var source = fateSuite.resolve(sample);
    assertThat(source).as("the FATE sample %s", sample).isRegularFile();
    var target = mediaRoot.resolve(source.getFileName());
    if (Files.exists(target)) {
      return target;
    }

    return Files.copy(source, target);
  }

  private Path writeOnce(String fileName, byte[] media) throws Exception {
    var target = mediaRoot.resolve(fileName);
    if (Files.exists(target)) {
      return target;
    }

    return Files.write(target, media);
  }

  // FFmpeg picks the muxer from the file name unless the arguments name one.
  private Path derive(String fileName, List<String> ffmpegArguments) throws Exception {
    var target = mediaRoot.resolve(fileName);
    if (Files.exists(target)) {
      return target;
    }

    return worker().generateMedia(target, ffmpegArguments);
  }

  private static String inContainer(Path sourceFile) {
    return CONTAINER_MEDIA_ROOT + sourceFile.getFileName();
  }

  private WorkerContainerFixture worker() {
    return workerFixture.worker();
  }

  private static Path fateSuite() {
    var location =
        Optional.ofNullable(System.getProperty(FATE_SUITE))
            .or(() -> Optional.ofNullable(System.getenv(FATE_SUITE)))
            .filter(value -> !value.isBlank());
    assertThat(location)
        .as(
            "Set the FATE_SUITE environment variable, or pass -DFATE_SUITE=<path>, to a local copy"
                + " of FFmpeg's FATE suite (rsync://fate-suite.ffmpeg.org/fate-suite/)")
        .isPresent();
    var suite = Path.of(location.orElseThrow());
    assertThat(suite).as("the FATE suite that FATE_SUITE names").isDirectory();
    return suite;
  }
}
