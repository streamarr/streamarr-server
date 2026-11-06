package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.AdvanceableSystemClock;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.MediaSegmentPublicationListener;
import com.streamarr.server.services.streaming.ProducerLifecycleService;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.events.MediaSegmentPublishedEvent;
import com.streamarr.server.services.streaming.remote.protocol.ProtoUuid;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.HeldBackJobAttempt;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Plays a stream session on a loopback worker over a real worker session while its job attempt
 * stores media far ahead of its viewer, and reads the job attempts that the server holds back (ADR
 * 0044, hold-back). Segments last 6 s, and the marks are the defaults: a job attempt is held back
 * once more than 60 s of its own media lies ahead of its viewer, and released at 30 s.
 */
@Tag("IntegrationTest")
@DisplayName("Hold-Back Integration Tests")
class HoldBackIT {

  private static final Path SOURCE_ROOT = Path.of("/media");
  private static final Duration REQUEST_LIMIT = Duration.ofSeconds(5);

  private final FakeSegmentStore segmentStore = new FakeSegmentStore();
  private final FakeRuntimeStreamSessionRegistry registry = new FakeRuntimeStreamSessionRegistry();
  private final FakeMediaFileContainerInfoRepository probeResults =
      new FakeMediaFileContainerInfoRepository();
  private final List<ManagedChannel> channels = new ArrayList<>();
  private final List<LoopbackWorker> workers = new ArrayList<>();
  private final List<BoundedTask<SegmentDelivery>> requests = new ArrayList<>();
  private final AdvanceableSystemClock clock = new AdvanceableSystemClock();
  private WorkerSessionServer server;
  private volatile MediaSegmentPublicationListener publications;
  private ProducerLifecycleService lifecycle;
  private HlsStreamingService streamingService;
  private SegmentDeliveryCoordinator coordinator;
  private MediaFile mediaFile;

  @BeforeEach
  void setUp() throws Exception {
    server =
        WorkerSessionServer.builder()
            .eventPublisher(this::forwardPublication)
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(segmentStore)
            .meterRegistry(new SimpleMeterRegistry())
            .build();
    server.start();

    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(8)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .build();
    var transcodeExecutor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, SOURCE_ROOT);
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(registry)
            .heldRequests(server.heldSegmentRequests())
            .clock(clock)
            .seekPriorityClock(clock)
            .build();
    lifecycle = rig.lifecycle();
    coordinator = rig.coordinator();
    publications = new MediaSegmentPublicationListener(lifecycle);

    var mediaFiles = new FakeMediaFileRepository();
    mediaFile =
        mediaFiles.save(
            MediaFile.builder()
                .filepathUri("file:///media/movies/feature.mkv")
                .filename("feature.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());
    probeResults.setDefaultProbe(encodedProbe());
    streamingService =
        HlsStreamingService.builder()
            .languagePreferences(noLanguagePreferences())
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
            .producerLifecycle(lifecycle)
            .deliveryCoordinator(coordinator)
            .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    for (var request : requests) {
      request.close();
    }

    for (var worker : workers) {
      worker.close();
    }

    for (var channel : channels) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    if (server != null) {
      server.close();
    }
  }

  @Test
  @DisplayName(
      "Should hold back a job attempt that runs more than the high-water mark ahead of its"
          + " viewer's reported position, and release it at the low-water mark without starting"
          + " another job attempt")
  void
      shouldHoldBackAJobAttemptThatRunsMoreThanTheHighWaterMarkAheadOfItsViewersReportedPositionAndReleaseItAtTheLowWaterMarkWithoutStartingAnotherJobAttempt()
          throws Exception {
    var worker = registerWorker();
    var playing = startPlaying(worker);
    registry.updatePlaybackState(playing.sessionId(), 0, PlaybackState.PLAYING);
    upload(playing, 1, 11);
    var held = worker.responsesThrough(EstablishWorkerSessionResponse::hasHoldBack);
    // 36 s in, segment 6 plays, and the job attempt's own media from segment 7 lasts 30 s.
    registry.updatePlaybackState(playing.sessionId(), 36, PlaybackState.PLAYING);
    assertThat(request(playing.sessionId(), segmentName(1)).await(REQUEST_LIMIT))
        .isInstanceOf(SegmentDelivery.Ready.class);
    var released = worker.responsesThrough(EstablishWorkerSessionResponse::hasHoldBack);

    // The first hold comes with segment 11: at segment 10 the lead equals the high-water mark, and
    // a hold then would release after 30 s.
    assertThat(held.getLast().getHoldBack().getJobAttemptsList())
        .containsExactly(heldBack(playing.job(), Duration.ofSeconds(36)));
    assertThat(released.getLast().getHoldBack().getJobAttemptsList()).isEmpty();
    assertThat(released).noneMatch(EstablishWorkerSessionResponse::hasStartVariant);
    assertThat(attemptOf(playing)).isEqualTo(playing.job().getJobAttemptId());
  }

  @Test
  @DisplayName(
      "Should measure the lead from the highest requested media segment when the viewer has sent"
          + " no timeline report")
  void shouldMeasureTheLeadFromTheHighestRequestedMediaSegmentWhenTheViewerHasSentNoTimelineReport()
      throws Exception {
    var worker = registerWorker();
    var playing = startPlaying(worker);
    upload(playing, 1, 11);
    var held = worker.responsesThrough(EstablishWorkerSessionResponse::hasHoldBack);

    assertThat(request(playing.sessionId(), segmentName(6)).await(REQUEST_LIMIT))
        .isInstanceOf(SegmentDelivery.Ready.class);
    var released = worker.responsesThrough(EstablishWorkerSessionResponse::hasHoldBack);

    assertThat(held.getLast().getHoldBack().getJobAttemptsList())
        .containsExactly(heldBack(playing.job(), Duration.ofSeconds(36)));
    assertThat(released.getLast().getHoldBack().getJobAttemptsList()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should release a held-back job attempt as soon as its player's request waits on a segment"
          + " that it has not produced")
  void
      shouldReleaseAHeldBackJobAttemptAsSoonAsItsPlayersRequestWaitsOnASegmentThatItHasNotProduced()
          throws Exception {
    var worker = registerWorker();
    var playing = startPlaying(worker);
    upload(playing, 1, 11);
    worker.responsesThrough(EstablishWorkerSessionResponse::hasHoldBack);

    var waiting = request(playing.sessionId(), segmentName(12));
    var released = worker.responsesThrough(EstablishWorkerSessionResponse::hasHoldBack);

    assertThat(released.getLast().getHoldBack().getJobAttemptsList()).isEmpty();
    assertThat(released).noneMatch(EstablishWorkerSessionResponse::hasStartVariant);
    upload(playing, 12, 12);
    assertThat(waiting.await(REQUEST_LIMIT)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  @Test
  @DisplayName(
      "Should hold a job attempt back again once its release time passes while its viewer stays"
          + " paused")
  void shouldHoldAJobAttemptBackAgainOnceItsReleaseTimePassesWhileItsViewerStaysPaused()
      throws Exception {
    var worker = registerWorker();
    var playing = startPlaying(worker);
    registry.updatePlaybackState(playing.sessionId(), 0, PlaybackState.PAUSED);
    upload(playing, 1, 11);
    worker.responsesThrough(EstablishWorkerSessionResponse::hasHoldBack);

    // The worker resumed the job attempt at its release time, and it stored its next segment.
    clock.advance(Duration.ofSeconds(37));
    upload(playing, 12, 12);
    var renewed = worker.responsesThrough(EstablishWorkerSessionResponse::hasHoldBack);

    assertThat(renewed.getLast().getHoldBack().getJobAttemptsList())
        .containsExactly(heldBack(playing.job(), Duration.ofSeconds(42)));
  }

  @Test
  @DisplayName(
      "Should start no other job attempt when its player's request waits on it long after the"
          + " worker released its hold")
  void shouldStartNoOtherJobAttemptWhenItsPlayersRequestWaitsOnItLongAfterTheWorkerReleasedItsHold()
      throws Exception {
    var worker = registerWorker();
    var playing = startPlaying(worker);
    registry.updatePlaybackState(playing.sessionId(), 0, PlaybackState.PAUSED);
    upload(playing, 1, 11);
    worker.responsesThrough(EstablishWorkerSessionResponse::hasHoldBack);

    // The hold released after 36 s; 48 s in, nothing stored for longer than the 10 s stall
    // threshold, yet the job attempt stored nothing because it was held.
    clock.advance(Duration.ofSeconds(48));
    registry.updatePlaybackState(playing.sessionId(), 0, PlaybackState.PLAYING);
    var waiting = request(playing.sessionId(), segmentName(12));
    await().atMost(REQUEST_LIMIT).until(waiting::isAwaitingALatchWithDeadline);
    upload(playing, 12, 12);
    assertThat(waiting.await(REQUEST_LIMIT)).isInstanceOf(SegmentDelivery.Ready.class);
    lifecycle.suspend(playing.sessionId());
    var sent = worker.responsesThrough(EstablishWorkerSessionResponse::hasStopVariant);

    assertThat(sent).noneMatch(EstablishWorkerSessionResponse::hasStartVariant);
    assertThat(sent.getLast().getStopVariant().getJobAttemptId())
        .isEqualTo(playing.job().getJobAttemptId());
  }

  @Test
  @DisplayName(
      "Should end the hold of a job attempt when its idle stream session is suspended, as the"
          + " session reaper does")
  void shouldEndTheHoldOfAJobAttemptWhenItsIdleStreamSessionIsSuspended() throws Exception {
    var worker = registerWorker();
    var playing = startPlaying(worker);
    var lastAccess = registry.findById(playing.sessionId()).orElseThrow().getLastAccessedAt();
    upload(playing, 1, 11);
    worker.responsesThrough(EstablishWorkerSessionResponse::hasHoldBack);
    assertThat(registry.findById(playing.sessionId()).orElseThrow().getLastAccessedAt())
        .as("a hold leaves the stream session idle")
        .isEqualTo(lastAccess);

    lifecycle.suspend(playing.sessionId());
    var released = worker.responsesThrough(EstablishWorkerSessionResponse::hasHoldBack);

    assertThat(released.getLast().getHoldBack().getJobAttemptsList()).isEmpty();
    assertThat(released)
        .anyMatch(
            response ->
                response.hasStopVariant()
                    && response
                        .getStopVariant()
                        .getJobAttemptId()
                        .equals(playing.job().getJobAttemptId()));
  }

  private LoopbackWorker registerWorker() throws InterruptedException {
    var channel = plaintextChannelBuilder(server.port(), WORKER_ID).build();
    channels.add(channel);
    var worker = LoopbackWorker.register(channel, WORKER_ID, 8);
    workers.add(worker);
    assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
    return worker;
  }

  // An HEVC source that every variant encodes.
  private static MediaProbe encodedProbe() {
    return defaultProbeBuilder()
        .videoCodec("hevc")
        .framerate(OptionalDouble.of(23.976))
        .bitrate(8_000_000L)
        .build();
  }

  // A stream session that starts at 480p, whose player has received its first media segment.
  private Playing startPlaying(LoopbackWorker worker) throws Exception {
    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).build();
    var session =
        accepted(
            streamingService.createSession(
                createStreamSessionCommand(mediaFile.getId(), UUID.randomUUID(), options)));
    var job = worker.nextJobs(1).getFirst();
    worker.reportStarted(job);
    var playing = new Playing(session.getSessionId(), job, worker);
    var first = request(playing.sessionId(), segmentName(0));
    upload(playing, 0, 0);
    assertThat(first.await(REQUEST_LIMIT)).isInstanceOf(SegmentDelivery.Ready.class);
    return playing;
  }

  private Uuid attemptOf(Playing playing) {
    var session = registry.findById(playing.sessionId()).orElseThrow();
    return ProtoUuid.toProto(session.getVariantHandle("480p").orElseThrow().attemptId());
  }

  // java.time.Duration and the protobuf Duration share a simple name.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static HeldBackJobAttempt heldBack(VariantJob job, Duration releaseAfter) {
    return HeldBackJobAttempt.newBuilder()
        .setJobAttemptId(job.getJobAttemptId())
        .setReleaseAfter(
            com.google.protobuf.Duration.newBuilder().setSeconds(releaseAfter.toSeconds()))
        .build();
  }

  // The loopback worker uploads each media segment of its job attempt over the worker contract.
  private static void upload(Playing playing, int firstSegment, int lastSegment) throws Exception {
    for (var index = firstSegment; index <= lastSegment; index++) {
      playing
          .worker()
          .uploadMediaSegment(playing.job(), index)
          .get(REQUEST_LIMIT.toSeconds(), TimeUnit.SECONDS);
    }
  }

  private BoundedTask<SegmentDelivery> request(UUID sessionId, String segmentName) {
    var delivery = BoundedTask.start(() -> coordinator.deliver(sessionId, "480p", segmentName));
    requests.add(delivery);
    return delivery;
  }

  // A publication reaches seek priority as the application's event does.
  private void forwardPublication(Object event) {
    if (event instanceof MediaSegmentPublishedEvent published) {
      publications.onMediaSegmentPublished(published);
    }
  }

  private static String segmentName(int index) {
    return SegmentNames.storedSegmentName("480p", SegmentNames.mediaSegmentName(index));
  }

  private record Playing(UUID sessionId, VariantJob job, LoopbackWorker worker) {}
}
