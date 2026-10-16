package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
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
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Creates a stream session and plays it through the segment delivery coordinator while a loopback
 * worker receives the job attempts over a real worker session (ADR 0019).
 */
@Tag("IntegrationTest")
@DisplayName("On-Demand Variant Integration Tests")
class OnDemandVariantsIT {

  private static final Path SOURCE_ROOT = Path.of("/media");
  private static final byte[] SEGMENT_BYTES = {1};

  private final FakeSegmentStore segmentStore = new FakeSegmentStore();
  private WorkerSessionServer server;
  private ManagedChannel channel;
  private LoopbackWorker worker;
  private HlsStreamingService streamingService;
  private SegmentDeliveryCoordinator coordinator;
  private MediaFile mediaFile;

  @BeforeEach
  void setUp() throws Exception {
    server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(segmentStore)
            .meterRegistry(new SimpleMeterRegistry())
            .build();
    server.start();
    channel = plaintextChannelBuilder(server.port(), WORKER_ID).build();
    worker = LoopbackWorker.register(channel);
    assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();

    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(8)
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
    coordinator = rig.coordinator();

    var mediaFiles = new FakeMediaFileRepository();
    mediaFile =
        mediaFiles.save(
            MediaFile.builder()
                .filepathUri("file:///media/movies/feature.mkv")
                .filename("feature.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());
    var probeResults = new FakeMediaFileContainerInfoRepository();
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .videoCodec("hevc")
            .framerate(OptionalDouble.of(23.976))
            .bitrate(8_000_000L)
            .build());
    streamingService =
        HlsStreamingService.builder()
            .mediaFileRepository(mediaFiles)
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(playbackProbeServiceBuilder(probeResults).build())
            .transcodeDecisionService(
                new TranscodeDecisionService(properties, new SimpleMeterRegistry()))
            .qualityLadderService(new QualityLadderService())
            .properties(properties)
            .authorityGate(new FakePlaybackAuthorityGate())
            .runtimeRegistry(registry)
            .producerLifecycle(rig.lifecycle())
            .deliveryCoordinator(coordinator)
            .build();
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
      "Should run only the variant that the player fetches when a stream session starts, switches up and seeks")
  void shouldRunOnlyTheVariantThatThePlayerFetchesWhenAStreamSessionStartsSwitchesUpAndSeeks()
      throws Exception {
    var sessionId = startedSession().getSessionId();
    worker.awaitRunningJobs(Map.of("480p", 0));
    assertThat(storeAndAwait(request(sessionId, "480p", "480p/segment0.m4s")))
        .isInstanceOf(SegmentDelivery.Ready.class);

    var switchUpInit = request(sessionId, "1080p", "1080p/init.mp4");
    worker.awaitRunningJobs(Map.of("480p", 0, "1080p", 0));
    assertThat(storeAndAwait(switchUpInit)).isInstanceOf(SegmentDelivery.Ready.class);
    var switchUpSegment = request(sessionId, "1080p", "1080p/segment1.m4s");
    worker.awaitRunningJobs(Map.of("1080p", 0));
    assertThat(storeAndAwait(switchUpSegment)).isInstanceOf(SegmentDelivery.Ready.class);

    var seek = request(sessionId, "1080p", "1080p/segment100.m4s");
    worker.awaitRunningJobs(Map.of("1080p", 100));
    assertThat(storeAndAwait(seek)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(List.of("480p", "720p", "360p"))
        .noneMatch(variantLabel -> server.isRunning(sessionId, variantLabel));
    assertThat(server.isRunning(sessionId, "1080p")).isTrue();
  }

  private StreamSession startedSession() {
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();
    return accepted(
        streamingService.createSession(
            createStreamSessionCommand(mediaFile.getId(), UUID.randomUUID(), options)));
  }

  private PendingRequest request(UUID sessionId, String variantLabel, String segmentName) {
    return new PendingRequest(
        sessionId,
        variantLabel,
        segmentName,
        CompletableFuture.supplyAsync(
            () -> coordinator.deliver(sessionId, variantLabel, segmentName)));
  }

  // The loopback worker uploads nothing, so the test stores each segment after the server has
  // started or stopped the job attempts that the request needs. The test then wakes the requests
  // held on the segment's variant, as a stored upload does.
  private SegmentDelivery storeAndAwait(PendingRequest pending) throws Exception {
    segmentStore.addSegment(pending.sessionId(), pending.segmentName(), SEGMENT_BYTES);
    server.heldSegmentRequests().wakeVariant(pending.sessionId(), pending.variantLabel());
    return pending.delivery().get(5, TimeUnit.SECONDS);
  }

  private record PendingRequest(
      UUID sessionId,
      String variantLabel,
      String segmentName,
      CompletableFuture<SegmentDelivery> delivery) {}
}
