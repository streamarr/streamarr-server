package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static com.streamarr.server.support.OutcomeTestSupport.rejectionOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.UploadBudgetFixture;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Creates stream sessions that copy or encode the video while a loopback worker with one job slot
 * receives their job attempts over a real worker session, under a transcode limit of one.
 */
@Tag("IntegrationTest")
@DisplayName("Copy Worker Slots Integration Tests")
class CopyWorkerSlotsIT {

  private static final Path SOURCE_ROOT = Path.of("/media");

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
            .sessionMutex(new MutexFactory<>())
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
            .uploadBudget(UploadBudgetFixture.OFF)
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

  @Test
  @DisplayName(
      "Should start two copies and an encode and refuse a second encode when the worker has one"
          + " slot")
  void shouldStartTwoCopiesAndAnEncodeAndRefuseASecondEncodeWhenTheWorkerHasOneSlot()
      throws Exception {
    // The encode starts beside a copy, and the second copy while the encode holds the only slot
    // and the only place under the transcode limit.
    var remux = startedSession(defaultProbeBuilder().build());
    var encode = startedSession(hevcProbe());
    var copyWithAudioEncode = startedSession(defaultProbeBuilder().audioCodec("flac").build());

    var secondEncode = streamingService.createSession(sessionCommand(hevcProbe()));

    var jobs = worker.nextJobs(3);
    assertThat(jobs)
        .extracting(job -> fromProto(job.getStreamSessionId()), job -> job.getDecision().getMode())
        .containsExactly(
            tuple(remux.getSessionId(), TranscodeMode.TRANSCODE_MODE_REMUX),
            tuple(encode.getSessionId(), TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE),
            tuple(
                copyWithAudioEncode.getSessionId(), TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE));
    assertThat(jobs)
        .allSatisfy(
            job ->
                assertThat(
                        server.isRunning(
                            fromProto(job.getStreamSessionId()),
                            job.getVariant().getVariantLabel()))
                    .isTrue());
    assertThat(rejectionOf(secondEncode))
        .isInstanceOf(CreateStreamSessionRejection.TranscodeCapacityUnavailable.class);
  }

  // The worker lists only libx264, so the client's missing HEVC entry makes the session encode.
  private static MediaProbe hevcProbe() {
    return defaultProbeBuilder()
        .videoCodec("hevc")
        .framerate(OptionalDouble.of(23.976))
        .bitrate(8_000_000L)
        .build();
  }

  private StreamSession startedSession(MediaProbe probe) {
    return accepted(streamingService.createSession(sessionCommand(probe)));
  }

  private CreateStreamSessionCommand sessionCommand(MediaProbe probe) {
    probeResults.setDefaultProbe(probe);
    var mediaFile =
        mediaFiles.save(
            MediaFile.builder()
                .filepathUri("file:///media/movies/" + UUID.randomUUID() + ".mkv")
                .filename("feature.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());
    return createStreamSessionCommandBuilder(mediaFile.getId(), UUID.randomUUID())
        .options(StreamingOptions.builder().quality(VideoQuality.AUTO).build())
        .build();
  }
}
