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

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.PlaybackState;
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
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.YieldToSeekCommand;
import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * Plays a steady stream session and seeks another one on loopback workers over real worker
 * sessions, and reads which job attempts the server tells the seek's worker to pause while the
 * seek's first segment is pending (ADR 0044, seek priority).
 */
@Tag("IntegrationTest")
@DisplayName("Seek Priority Integration Tests")
class SeekPriorityIT {

  private static final Path SOURCE_ROOT = Path.of("/media");
  private static final byte[] SEGMENT_BYTES = {1};
  private static final UUID OTHER_WORKER_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  // 6 s segments; a job attempt yields once it runs more than three segments ahead.
  private static final int LEAD_SEGMENTS = 3;
  private static final Duration HOLD_LIMIT = Duration.ofSeconds(3);
  private static final int SEEK_SEGMENT = 100;

  private final FakeSegmentStore segmentStore = new FakeSegmentStore();
  private final FakeRuntimeStreamSessionRegistry registry = new FakeRuntimeStreamSessionRegistry();
  private final FakeMediaFileContainerInfoRepository probeResults =
      new FakeMediaFileContainerInfoRepository();
  private final List<ManagedChannel> channels = new ArrayList<>();
  private final List<LoopbackWorker> workers = new ArrayList<>();
  private WorkerSessionServer server;
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

    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(8)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .seekPriorityLeadSegments(LEAD_SEGMENTS)
            .seekPriorityHoldLimit(HOLD_LIMIT)
            .build();
    var transcodeExecutor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, SOURCE_ROOT);
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
    probeResults.setDefaultProbe(
        defaultProbeBuilder()
            .videoCodec("hevc")
            .framerate(OptionalDouble.of(23.976))
            .bitrate(8_000_000L)
            .build());
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
            .producerLifecycle(rig.lifecycle())
            .deliveryCoordinator(coordinator)
            .build();
  }

  @AfterEach
  void tearDown() throws Exception {
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
      "Should name the steady stream session's job attempt while the seek's first segment is"
          + " pending, and end the yield once that segment is delivered")
  void
      shouldNameTheSteadyStreamSessionsJobAttemptWhileTheSeeksFirstSegmentIsPendingAndEndTheYieldOnceThatSegmentIsDelivered()
          throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    var seeking = startPlaying(worker);

    var seek = request(seeking.sessionId(), segmentName(SEEK_SEGMENT));
    var untilYield = worker.responsesThrough(EstablishWorkerSessionResponse::hasYieldToSeek);

    var seekJob = startedJobs(untilYield).getLast();
    var yieldCommand = untilYield.getLast().getYieldToSeek();
    assertThat(seekJob.getExecution().getStartSequenceNumber()).isEqualTo(SEEK_SEGMENT);
    assertThat(yieldCommand.getJobAttemptIdsList()).containsExactly(steady.job().getJobAttemptId());
    assertThat(yieldCommand.getHoldLimit().getSeconds()).isEqualTo(HOLD_LIMIT.toSeconds());
    assertThat(storeAndAwait(seek)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(nextYield(worker).getJobAttemptIdsList()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should never name a job attempt that runs at most the lead limit ahead of its reported"
          + " position")
  void shouldNeverNameAJobAttemptThatRunsAtMostTheLeadLimitAheadOfItsReportedPosition()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    // 36 s lies in segment 6, three segments behind the last delivered one.
    registry.updatePlaybackState(steady.sessionId(), 36, PlaybackState.PLAYING);
    var seeking = startPlaying(worker);

    var seek = request(seeking.sessionId(), segmentName(SEEK_SEGMENT));
    assertThat(storeAndAwait(seek)).isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(yieldsUntilTheSteadyAttemptStops(worker, steady)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should measure the lead from the highest requested media segment when the viewer has sent"
          + " no timeline report")
  void shouldMeasureTheLeadFromTheHighestRequestedMediaSegmentWhenTheViewerHasSentNoTimelineReport()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    assertThat(storeAndAwait(request(steady.sessionId(), segmentName(1))))
        .isInstanceOf(SegmentDelivery.Ready.class);
    var seeking = startPlaying(worker);

    request(seeking.sessionId(), segmentName(SEEK_SEGMENT));

    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());
  }

  @Test
  @DisplayName(
      "Should leave out a job attempt whose highest requested media segment lies within the lead"
          + " limit when the viewer has sent no timeline report")
  void
      shouldLeaveOutAJobAttemptWhoseHighestRequestedMediaSegmentLiesWithinTheLeadLimitWhenTheViewerHasSentNoTimelineReport()
          throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    assertThat(storeAndAwait(request(steady.sessionId(), segmentName(6))))
        .isInstanceOf(SegmentDelivery.Ready.class);
    var seeking = startPlaying(worker);

    var seek = request(seeking.sessionId(), segmentName(SEEK_SEGMENT));
    assertThat(storeAndAwait(seek)).isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(yieldsUntilTheSteadyAttemptStops(worker, steady)).isEmpty();
  }

  @Test
  @DisplayName("Should name no job attempt of another worker than the seek's")
  void shouldNameNoJobAttemptOfAnotherWorkerThanTheSeeks() throws Exception {
    var steadyWorker = registerWorker(WORKER_ID, 1);
    var steady = startPlaying(steadyWorker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    var seekWorker = registerWorker(OTHER_WORKER_ID, 8);
    var seeking = startPlaying(seekWorker);

    var seek = request(seeking.sessionId(), segmentName(SEEK_SEGMENT));
    var seekJob = seekWorker.nextJobs(1).getFirst();
    assertThat(storeAndAwait(seek)).isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(seekJob.getExecution().getStartSequenceNumber()).isEqualTo(SEEK_SEGMENT);
    assertThat(yieldsUntilTheSteadyAttemptStops(steadyWorker, steady)).isEmpty();
    server.stopVariant(seeking.sessionId(), "480p");
    assertThat(
            seekWorker.responsesThrough(EstablishWorkerSessionResponse::hasStopVariant).stream()
                .filter(EstablishWorkerSessionResponse::hasYieldToSeek))
        .isEmpty();
  }

  private LoopbackWorker registerWorker(UUID workerId, int availableSlots)
      throws InterruptedException {
    var channel = plaintextChannelBuilder(server.port(), workerId).build();
    channels.add(channel);
    var worker = LoopbackWorker.register(channel, workerId, availableSlots);
    workers.add(worker);
    assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
    return worker;
  }

  // A stream session that starts at 480p, whose player has received its first media segment.
  private Playing startPlaying(LoopbackWorker worker) throws Exception {
    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).build();
    var session =
        accepted(
            streamingService.createSession(
                createStreamSessionCommand(mediaFile.getId(), UUID.randomUUID(), options)));
    var job = worker.nextJobs(1).getFirst();
    var playing = new Playing(session.getSessionId(), job);
    assertThat(storeAndAwait(request(playing.sessionId(), segmentName(0))))
        .isInstanceOf(SegmentDelivery.Ready.class);
    return playing;
  }

  private void deliverAhead(Playing playing, int lastSegment) {
    for (var index = 0; index <= lastSegment; index++) {
      segmentStore.addSegment(playing.sessionId(), segmentName(index), SEGMENT_BYTES);
    }
  }

  // The yields that reach the worker before the stop that the test sends after the seek, which
  // the worker session carries after any yield that the seek sent.
  private List<YieldToSeekCommand> yieldsUntilTheSteadyAttemptStops(
      LoopbackWorker worker, Playing steady) throws InterruptedException {
    server.stopVariant(steady.sessionId(), "480p");
    return worker
        .responsesThrough(
            response ->
                response.hasStopVariant()
                    && response
                        .getStopVariant()
                        .getJobAttemptId()
                        .equals(steady.job().getJobAttemptId()))
        .stream()
        .filter(EstablishWorkerSessionResponse::hasYieldToSeek)
        .map(EstablishWorkerSessionResponse::getYieldToSeek)
        .toList();
  }

  private static YieldToSeekCommand nextYield(LoopbackWorker worker) throws InterruptedException {
    return worker
        .responsesThrough(EstablishWorkerSessionResponse::hasYieldToSeek)
        .getLast()
        .getYieldToSeek();
  }

  private static List<VariantJob> startedJobs(List<EstablishWorkerSessionResponse> responses) {
    return responses.stream()
        .filter(EstablishWorkerSessionResponse::hasStartVariant)
        .map(response -> response.getStartVariant().getJob())
        .toList();
  }

  private static String segmentName(int index) {
    return "480p/segment" + index + ".m4s";
  }

  private PendingRequest request(UUID sessionId, String segmentName) {
    return new PendingRequest(
        sessionId,
        segmentName,
        CompletableFuture.supplyAsync(() -> coordinator.deliver(sessionId, "480p", segmentName)));
  }

  // The loopback worker uploads nothing, so the test stores each segment and wakes the requests
  // held on its variant, as a stored upload does.
  private SegmentDelivery storeAndAwait(PendingRequest pending) throws Exception {
    segmentStore.addSegment(pending.sessionId(), pending.segmentName(), SEGMENT_BYTES);
    server.heldSegmentRequests().wakeVariant(pending.sessionId(), "480p");
    return pending.delivery().get(5, TimeUnit.SECONDS);
  }

  private record Playing(UUID sessionId, VariantJob job) {}

  private record PendingRequest(
      UUID sessionId, String segmentName, CompletableFuture<SegmentDelivery> delivery) {}
}
