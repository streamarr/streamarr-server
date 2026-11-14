package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.AssumedSourceMatrix;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fixtures.StoredSegments;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.properties.StreamingProperties;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.assertj.core.util.DoubleComparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("SmokeTest")
@DisplayName("SDR Encode Color Smoke Tests")
class SdrEncodeColorSmokeTest {

  private static final int SEGMENT_SECONDS = 6;
  private static final int SOURCE_SECONDS = 2;
  private static final Duration DELIVERY_LIMIT = Duration.ofSeconds(60);
  // Read as BT.601 and written as BT.709, this color comes out as 58, 105, 230, so every
  // component moves by at least 3. The tolerance, half of that, passes one code value of encoder
  // or scaler rounding in any component and fails every component of the shifted color.
  private static final MeanSamples SOURCE_COLOR = new MeanSamples(63, 102, 240);
  private static final double SAMPLE_TOLERANCE = 1.5;
  private static final int CENTER_BLOCK = 16;
  // The ladder lists 480p first; this variant encodes the picture at the source's size.
  private static final String SOURCE_SIZE_VARIANT = "720p";

  private FakeMediaFileRepository mediaFileRepository;
  private FakeMediaFileContainerInfoRepository probeRepository;
  private LocalSegmentStore segmentStore;
  private HlsStreamingService streamingService;
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
    workerFixture.creditUploadsTo(rig.lifecycle());
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
      "Should keep the source's colors when the server encodes a high-definition SDR source whose"
          + " video names no matrix")
  void
      shouldKeepTheSourcesColorsWhenTheServerEncodesAHighDefinitionSdrSourceWhoseVideoNamesNoMatrix()
          throws Exception {
    var session = encodeOf(flatColorSource());
    var decision = session.getTranscodeDecision();
    assertThat(decision.transcodeMode().copiesVideo()).isFalse();
    assertThat(decision.assumedSourceMatrix()).contains(AssumedSourceMatrix.BT709);

    var delivered = deliveredFirstSegment(session);

    assertThat(workerFixture.worker().firstVideoFrame(delivered))
        .containsEntry("width", "1280")
        .containsEntry("height", "720")
        .containsEntry("pix_fmt", "yuv420p")
        .containsEntry("color_space", "bt709")
        .containsEntry("color_primaries", "bt709")
        .containsEntry("color_transfer", "bt709");
    assertThat(centerSamples(delivered))
        .as("the mean luma, Cb and Cr at the center of the delivered picture")
        .usingRecursiveComparison()
        .withComparatorForType(new DoubleComparator(SAMPLE_TOLERANCE), Double.class)
        .isEqualTo(SOURCE_COLOR);
  }

  // 8-bit 4:2:0 H.264 of one color, whose video stream names no matrix, primaries or transfer.
  private Path flatColorSource() throws Exception {
    var color =
        "geq=lum=%s:cb=%s:cr=%s"
            .formatted(SOURCE_COLOR.luma(), SOURCE_COLOR.cb(), SOURCE_COLOR.cr());
    var source =
        workerFixture
            .worker()
            .generateMedia(
                sourceRoot.resolve("flat-color-720p.mp4"),
                List.of(
                    "-f",
                    "lavfi",
                    "-i",
                    "color=size=1280x720:rate=24:duration=%d,format=yuv420p,%s"
                        .formatted(SOURCE_SECONDS, color),
                    "-c:v",
                    "libx264",
                    "-preset",
                    "ultrafast",
                    "-pix_fmt",
                    "yuv420p",
                    "-an"));
    assertThat(workerFixture.worker().firstVideoFrame(source))
        .as("the source's first decoded frame")
        .containsEntry("width", "1280")
        .containsEntry("height", "720")
        .containsEntry("pix_fmt", "yuv420p")
        .containsEntry("color_space", "unknown")
        .containsEntry("color_primaries", "unknown")
        .containsEntry("color_transfer", "unknown");
    assertThat(centerSamples(source))
        .as("the mean luma, Cb and Cr at the center of the source's picture")
        .isEqualTo(SOURCE_COLOR);
    return source;
  }

  // The default client declares H.264, so only the encode-only constraint makes the server encode.
  private StreamSession encodeOf(Path source) throws Exception {
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
                .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                .build()));
  }

  private void storeProbe(Path source) {
    var outcome =
        probes.probe(
            ProbeExecutionRequest.builder()
                .sourcePath(source)
                .attemptId(UUID.randomUUID())
                .probeVersion(ProbeVersion.CURRENT)
                .priority(ProbePriority.BACKGROUND)
                .build());

    assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
    probeRepository.setDefaultOutcome((ProbeOutcome.Success) outcome);
  }

  // The source-size variant's initialization segment and first media segment, as the coordinator
  // delivers them to a player, in one decodable file under the source root.
  private Path deliveredFirstSegment(StreamSession session) throws Exception {
    var mediaSegment = delivered(session, SegmentNames.mediaSegmentName(0));
    var playable = new ByteArrayOutputStream();
    playable.writeBytes(delivered(session, SegmentNames.INITIALIZATION_SEGMENT_NAME));
    playable.writeBytes(mediaSegment);
    return Files.write(
        sourceRoot.resolve("delivered-" + SOURCE_SIZE_VARIANT + ".mp4"), playable.toByteArray());
  }

  private byte[] delivered(StreamSession session, String segmentName) throws Exception {
    var storedName = SegmentNames.storedSegmentName(SOURCE_SIZE_VARIANT, segmentName);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var delivery =
          executor.submit(
              () -> coordinator.deliver(session.getSessionId(), SOURCE_SIZE_VARIANT, storedName));
      try {
        assertThat(delivery).as("the delivery of %s", storedName).succeedsWithin(DELIVERY_LIMIT);
        return StoredSegments.bytes(delivery.get());
      } finally {
        delivery.cancel(true);
      }
    }
  }

  // The raw output keeps the decoder's 4:2:0 planes, so no conversion touches the samples.
  private MeanSamples centerSamples(Path media) throws Exception {
    var worker = workerFixture.worker();
    var picture =
        Files.readAllBytes(
            worker.generateMedia(
                temporaryDirectory.resolve(media.getFileName() + ".yuv"),
                List.of(
                    "-i",
                    worker.containerPath(media),
                    "-vf",
                    "crop=%d:%d".formatted(CENTER_BLOCK, CENTER_BLOCK),
                    "-frames:v",
                    "1",
                    "-f",
                    "rawvideo")));
    var lumaSamples = CENTER_BLOCK * CENTER_BLOCK;
    var chromaSamples = lumaSamples / 4;
    assertThat(picture)
        .as("one 8-bit 4:2:0 picture of the center block")
        .hasSize(lumaSamples + 2 * chromaSamples);
    return new MeanSamples(
        mean(picture, 0, lumaSamples),
        mean(picture, lumaSamples, chromaSamples),
        mean(picture, lumaSamples + chromaSamples, chromaSamples));
  }

  private static double mean(byte[] samples, int offset, int count) {
    return IntStream.range(offset, offset + count)
        .map(index -> Byte.toUnsignedInt(samples[index]))
        .average()
        .orElseThrow();
  }

  record MeanSamples(double luma, double cb, double cr) {}
}
