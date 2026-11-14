package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.transcode.v1.TranscodeMode;
import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Creates stream sessions that copy or encode while a loopback worker receives their job attempts
 * over a real worker session, and reads the advertised end that each job attempt carries (ADR 0037,
 * media end).
 */
@Tag("IntegrationTest")
@DisplayName("Job Attempt Advertised End Integration Tests")
class JobAttemptAdvertisedEndIT {

  private static final Path SOURCE_ROOT = Path.of("/media");
  // No scan has read the streams' end times, so the media ends at the container's duration.
  private static final Duration MEDIA_END = Duration.ofMillis(60_018);

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeMediaFileContainerInfoRepository probeResults =
      new FakeMediaFileContainerInfoRepository();
  private WorkerSessionServer server;
  private ManagedChannel channel;
  private LoopbackWorker worker;
  private HlsStreamingService streamingService;

  @BeforeEach
  void setUp() throws Exception {
    var segmentStore = new FakeSegmentStore();
    server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(segmentStore)
            .meterRegistry(new SimpleMeterRegistry())
            .build();
    server.start();
    channel = plaintextChannelBuilder(server.port(), WORKER_ID).build();

    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(1)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .build();
    var transcodeExecutor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, SOURCE_ROOT);
    var registry = new FakeRuntimeStreamSessionRegistry();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(registry)
            .heldRequests(server.heldSegmentRequests())
            .build();
    streamingService =
        HlsStreamingService.builder()
            .meterRegistry(new SimpleMeterRegistry())
            .mediaFileRepository(mediaFiles)
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(playbackProbeServiceBuilder(probeResults).build())
            .transcodeDecisionService(
                new TranscodeDecisionService(properties, new SimpleMeterRegistry()))
            .qualityLadderService(new QualityLadderService())
            .properties(properties)
            .authorityGate(new FakePlaybackAuthorityGate())
            .languagePreferences(noLanguagePreferences())
            .runtimeRegistry(registry)
            .producerLifecycle(rig.lifecycle())
            .deliveryCoordinator(rig.coordinator())
            .build();

    worker = LoopbackWorker.register(channel, 1);
    assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (worker != null) {
      worker.close();
    }

    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    if (server != null) {
      server.close();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("probesThatCopyOrEncode")
  @DisplayName(
      "Should send the media end as the advertised end with the job attempt when the stream"
          + " session copies or encodes")
  void shouldSendTheMediaEndAsTheAdvertisedEndWithTheJobAttemptWhenTheSessionCopiesOrEncodes(
      String session, MediaProbe probe, TranscodeMode mode) throws Exception {
    probeResults.setDefaultProbe(probe);

    accepted(streamingService.createSession(sessionCommand()));

    var job = worker.nextJobs(1).getFirst();
    assertThat(job.getDecision().getMode()).isEqualTo(mode);
    var execution = job.getExecution();
    assertThat(execution.hasAdvertisedEnd())
        .as("the job attempt carries an advertised end")
        .isTrue();
    assertThat(execution.getAdvertisedEnd().getSeconds()).isEqualTo(MEDIA_END.getSeconds());
    assertThat(execution.getAdvertisedEnd().getNanos()).isEqualTo(MEDIA_END.getNano());
  }

  // The worker lists only libx264, so the client's missing HEVC entry makes the session encode.
  static Stream<Arguments> probesThatCopyOrEncode() {
    return Stream.of(
        Arguments.of(
            "a copy of the video and the audio",
            probeBuilder().build(),
            TranscodeMode.TRANSCODE_MODE_REMUX),
        Arguments.of(
            "a copy of the video beside an audio encode",
            probeBuilder().audioCodec("flac").build(),
            TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE),
        Arguments.of(
            "a video encode",
            probeBuilder()
                .videoCodec("hevc")
                .framerate(OptionalDouble.of(23.976))
                .bitrate(8_000_000L)
                .build(),
            TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE));
  }

  private static MediaProbe.MediaProbeBuilder probeBuilder() {
    return defaultProbeBuilder()
        .duration(MEDIA_END)
        .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(2), MEDIA_END));
  }

  private CreateStreamSessionCommand sessionCommand() {
    var mediaFile =
        mediaFiles.save(
            MediaFile.builder()
                .filepathUri("file:///media/movies/" + UUID.randomUUID() + ".mkv")
                .filename("feature.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());
    return createStreamSessionCommandBuilder(mediaFile.getId(), UUID.randomUUID()).build();
  }
}
