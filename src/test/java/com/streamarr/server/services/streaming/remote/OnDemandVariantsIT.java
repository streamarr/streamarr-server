package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static com.streamarr.server.support.OutcomeTestSupport.rejectionOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
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
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final MutexFactory<UUID> sessionMutex = new MutexFactory<>();
  private final FakeMediaFileContainerInfoRepository probeResults =
      new FakeMediaFileContainerInfoRepository();
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
            .meterRegistry(meterRegistry)
            .build();
    server.start();
    channel = plaintextChannelBuilder(server.port(), WORKER_ID).build();

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
            .sessionMutex(sessionMutex)
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
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .videoCodec("hevc")
            .framerate(OptionalDouble.of(23.976))
            .bitrate(8_000_000L)
            .build());
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
      "Should run only the variant that the player fetches, and the variant it leaves until the switch delivers, when a stream session starts, switches up and seeks")
  void
      shouldRunOnlyTheVariantThatThePlayerFetchesAndTheVariantItLeavesUntilTheSwitchDeliversWhenAStreamSessionStartsSwitchesUpAndSeeks()
          throws Exception {
    registerWorker(8);
    var sessionId = startedSession().getSessionId();
    worker.awaitRunningJobs(Map.of("480p", 0));
    assertThat(storeAndAwait(request(sessionId, "480p", "480p/segment0.m4s")))
        .isInstanceOf(SegmentDelivery.Ready.class);

    var switchUpInit = request(sessionId, "1080p", "1080p/init.mp4");
    worker.awaitRunningJobs(Map.of("480p", 0, "1080p", 1));
    assertThat(storeAndAwait(switchUpInit)).isInstanceOf(SegmentDelivery.Ready.class);
    var switchUpSegment = request(sessionId, "1080p", "1080p/segment1.m4s");
    awaitHeldRequests(1);
    assertThat(server.isRunning(sessionId, "480p")).isTrue();
    assertThat(storeAndAwait(switchUpSegment)).isInstanceOf(SegmentDelivery.Ready.class);
    worker.awaitRunningJobs(Map.of("1080p", 1));

    var seek = request(sessionId, "1080p", "1080p/segment100.m4s");
    worker.awaitRunningJobs(Map.of("1080p", 100));
    assertThat(storeAndAwait(seek)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(List.of("480p", "720p", "360p"))
        .noneMatch(variantLabel -> server.isRunning(sessionId, variantLabel));
    assertThat(server.isRunning(sessionId, "1080p")).isTrue();
  }

  @Test
  @DisplayName(
      "Should stop the variant that the player leaves to start the switched variant when the worker has no free job slot")
  void
      shouldStopTheVariantThatThePlayerLeavesToStartTheSwitchedVariantWhenTheWorkerHasNoFreeJobSlot()
          throws Exception {
    registerWorker(1);
    var sessionId = startedSession().getSessionId();
    worker.awaitRunningJobs(Map.of("480p", 0));
    assertThat(storeAndAwait(request(sessionId, "480p", "480p/segment0.m4s")))
        .isInstanceOf(SegmentDelivery.Ready.class);

    var switchUp = request(sessionId, "1080p", "1080p/segment1.m4s");

    worker.awaitRunningJobs(Map.of("1080p", 1));
    assertThat(storeAndAwait(switchUp)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(server.isRunning(sessionId, "480p")).isFalse();
  }

  @Test
  @DisplayName(
      "Should stop another stream session's left variant to admit a stream session when the worker has no free job slot")
  void
      shouldStopAnotherStreamSessionsLeftVariantToAdmitAStreamSessionWhenTheWorkerHasNoFreeJobSlot()
          throws Exception {
    registerWorker(3);
    var switchingId = startedSession().getSessionId();
    worker.awaitRunningJobs(Map.of("480p", 0));
    assertThat(storeAndAwait(request(switchingId, "480p", "480p/segment0.m4s")))
        .isInstanceOf(SegmentDelivery.Ready.class);
    var switchUp = request(switchingId, "1080p", "1080p/segment1.m4s");
    worker.awaitRunningJobs(Map.of("480p", 0, "1080p", 1));
    awaitHeldRequests(1);
    var switchingMutex = sessionMutex.getMutex(switchingId);
    await().atMost(Duration.ofSeconds(5)).until(() -> !switchingMutex.isLocked());
    var otherId = startedSession().getSessionId();

    var admittedId = startedSession().getSessionId();

    assertThat(server.isRunning(switchingId, "480p")).isFalse();
    assertThat(server.isRunning(admittedId, "480p")).isTrue();
    assertThat(server.isRunning(otherId, "480p")).isTrue();
    assertThat(storeAndAwait(switchUp)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  @Test
  @DisplayName(
      "Should keep another stream session's left variant when a stream session whose encode has no frame rate is refused while the worker has no free job slot")
  void
      shouldKeepAnotherStreamSessionsLeftVariantWhenAStreamSessionWhoseEncodeHasNoFrameRateIsRefusedWhileTheWorkerHasNoFreeJobSlot()
          throws Exception {
    registerWorker(3);
    var switchingId = startedSession().getSessionId();
    worker.awaitRunningJobs(Map.of("480p", 0));
    assertThat(storeAndAwait(request(switchingId, "480p", "480p/segment0.m4s")))
        .isInstanceOf(SegmentDelivery.Ready.class);
    var switchUp = request(switchingId, "1080p", "1080p/segment1.m4s");
    worker.awaitRunningJobs(Map.of("480p", 0, "1080p", 1));
    awaitHeldRequests(1);
    var switchingMutex = sessionMutex.getMutex(switchingId);
    await().atMost(Duration.ofSeconds(5)).until(() -> !switchingMutex.isLocked());
    startedSession();
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .videoCodec("hevc")
            .framerate(OptionalDouble.empty())
            .bitrate(8_000_000L)
            .build());

    var outcome = streamingService.createSession(sessionCommand());

    assertThat(rejectionOf(outcome))
        .isInstanceOf(CreateStreamSessionRejection.TranscodeCapacityUnavailable.class);
    assertThat(server.isRunning(switchingId, "480p")).isTrue();
    assertThat(storeAndAwait(switchUp)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  private void registerWorker(int availableSlots) throws InterruptedException {
    worker = LoopbackWorker.register(channel, availableSlots);
    assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
  }

  private StreamSession startedSession() {
    return accepted(streamingService.createSession(sessionCommand()));
  }

  private CreateStreamSessionCommand sessionCommand() {
    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).build();
    return createStreamSessionCommand(mediaFile.getId(), UUID.randomUUID(), options);
  }

  // A request is held only after the server has applied the on-demand rules to it.
  private void awaitHeldRequests(int count) {
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                meterRegistry.get("streamarr.streaming.held_segment_requests").gauge().value()
                    == count);
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
