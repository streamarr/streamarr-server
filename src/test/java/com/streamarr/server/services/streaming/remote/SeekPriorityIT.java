package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.directPlaySessionBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
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
import com.streamarr.server.fixtures.UploadBudgetFixture;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.MediaSegmentPublicationListener;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.events.MediaSegmentPublishedEvent;
import com.streamarr.server.services.streaming.remote.PausingSegmentStore.PublicationPause;
import com.streamarr.server.services.streaming.remote.protocol.ProtoUuid;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.YieldToSeekCommand;
import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
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
  // 6 s segments; a job attempt yields once the media stored ahead of its viewer outlasts the hold
  // limit, its next segment and the margin, which takes more than three segments.
  private static final Duration POSITION_MARGIN = Duration.ofSeconds(9);
  private static final Duration HOLD_LIMIT = Duration.ofSeconds(3);
  private static final int SEEK_SEGMENT = 100;
  private static final Duration REQUEST_LIMIT = Duration.ofSeconds(5);

  private final FakeSegmentStore storedSegments = new FakeSegmentStore();
  private final PausingSegmentStore segmentStore = new PausingSegmentStore(storedSegments);
  private final FakeRuntimeStreamSessionRegistry registry = new FakeRuntimeStreamSessionRegistry();
  private final FakeMediaFileContainerInfoRepository probeResults =
      new FakeMediaFileContainerInfoRepository();
  private final List<ManagedChannel> channels = new ArrayList<>();
  private final List<LoopbackWorker> workers = new ArrayList<>();
  private final List<BoundedTask<SegmentDelivery>> requests = new ArrayList<>();
  private final AdvanceableSystemClock clock = new AdvanceableSystemClock();
  // Yields that arrived while the test read up to a seek's start, which the worker session can
  // carry before or after the start.
  private final Deque<YieldToSeekCommand> unreadYields = new ArrayDeque<>();
  private WorkerSessionServer server;
  private RemoteTranscodeExecutor transcodeExecutor;
  private volatile MediaSegmentPublicationListener publications;
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
            .seekPriorityPositionMargin(POSITION_MARGIN)
            .seekPriorityHoldLimit(HOLD_LIMIT)
            .build();
    transcodeExecutor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, SOURCE_ROOT);
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(registry)
            .heldRequests(server.heldSegmentRequests())
            .seekPriorityClock(clock)
            .build();
    coordinator = rig.coordinator();
    publications = new MediaSegmentPublicationListener(rig.lifecycle());

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
            .producerLifecycle(rig.lifecycle())
            .deliveryCoordinator(coordinator)
            .uploadBudget(UploadBudgetFixture.OFF)
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
      "Should never name a job attempt that runs at most three segments ahead of its reported"
          + " position")
  void shouldNeverNameAJobAttemptThatRunsAtMostThreeSegmentsAheadOfItsReportedPosition()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    // 36 s lies in segment 6, three segments behind the last delivered one.
    registry.updatePlaybackState(steady.sessionId(), 36, PlaybackState.PLAYING);
    var seeking = startPlaying(worker);

    var seek = seek(worker, seeking);
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

    seek(worker, seeking);

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

    var seek = seek(worker, seeking);
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

  @Test
  @DisplayName(
      "Should name the steady stream session's job attempt when a direct-play stream session has"
          + " reported its position")
  void
      shouldNameTheSteadyStreamSessionsJobAttemptWhenADirectPlayStreamSessionHasReportedItsPosition()
          throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    // As the streaming service builds it: no job attempt, so no transcode decision.
    var directPlay = directPlaySessionBuilder().transcodeDecision(null).build();
    registry.save(directPlay);
    registry.updatePlaybackState(directPlay.getSessionId(), 30, PlaybackState.PLAYING);
    var seeking = startPlaying(worker);

    seek(worker, seeking);

    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());
  }

  @Test
  @DisplayName(
      "Should ignore a timeline report from before the stream session's own seek when a later"
          + " request touches the stream session")
  void shouldIgnoreATimelineReportFromBeforeTheStreamSessionsOwnSeekWhenALaterRequestTouchesIt()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    var steadySeek = request(steady.sessionId(), segmentName(50));
    var relocated = new Playing(steady.sessionId(), worker.nextJobs(1).getFirst());
    assertThat(storeAndAwait(steadySeek)).isInstanceOf(SegmentDelivery.Ready.class);
    storeSegments(relocated, 51, 52);
    registry.touch(steady.sessionId(), Instant.now());
    var seeking = startPlaying(worker);

    var seek = seek(worker, seeking);
    assertThat(storeAndAwait(seek)).isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(relocated.job().getExecution().getStartSequenceNumber()).isEqualTo(50);
    assertThat(yieldsUntilTheSteadyAttemptStops(worker, relocated)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should never name a job attempt that a player's request started past its reported position"
          + " before that job attempt has delivered its first segment")
  void shouldNeverNameAJobAttemptThatAPlayersRequestStartedBeforeItHasDeliveredItsFirstSegment()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    request(steady.sessionId(), "720p", "720p/segment100.m4s");
    var onDemand = new Playing(steady.sessionId(), worker.nextJobs(1).getFirst());
    var seeking = startPlaying(worker);

    var seek = seek(worker, seeking);
    assertThat(storeAndAwait(seek)).isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(onDemand.job().getVariant().getVariantLabel()).isEqualTo("720p");
    assertThat(yieldsUntilTheSteadyAttemptStops(worker, onDemand)).isEmpty();
  }

  @Test
  @DisplayName("Should never name a job attempt whose player waits on its next segment")
  void shouldNeverNameAJobAttemptWhosePlayerWaitsOnItsNextSegment() throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    request(steady.sessionId(), segmentName(10));
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> server.heldSegmentRequests().holdsRequestsOn(steady.sessionId(), "480p"));
    var seeking = startPlaying(worker);

    var seek = seek(worker, seeking);
    assertThat(storeAndAwait(seek)).isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(yieldsUntilTheSteadyAttemptStops(worker, steady)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should resume a named job attempt as soon as its player's request waits on its next"
          + " segment")
  void shouldResumeANamedJobAttemptAsSoonAsItsPlayersRequestWaitsOnItsNextSegment()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    var seeking = startPlaying(worker);
    seek(worker, seeking);
    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());

    request(steady.sessionId(), segmentName(10));

    assertThat(nextYield(worker).getJobAttemptIdsList()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should place a viewer at its seek's first segment before its player requests another")
  void shouldPlaceAViewerAtItsSeeksFirstSegmentBeforeItsPlayerRequestsAnother() throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var sought = startPlaying(worker);
    assertThat(storeAndAwait(seek(worker, sought, 20))).isInstanceOf(SegmentDelivery.Ready.class);
    storeSegments(sought, 21, 30);
    var seeking = startPlaying(worker);

    seek(worker, seeking);

    assertThat(nextYield(worker).getJobAttemptIdsList()).containsExactly(attemptOf(sought));
  }

  @Test
  @DisplayName(
      "Should never name a job attempt that reproduces media stored before it started, and name it"
          + " once it stores media past it")
  void shouldNeverNameAJobAttemptThatReproducesMediaStoredBeforeItStartedAndNameItOncePast()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    var reproducing = startPlaying(worker);
    storeSegments(reproducing, 21, 40);
    assertThat(storeAndAwait(seek(worker, reproducing, 20)))
        .isInstanceOf(SegmentDelivery.Ready.class);
    var reproducingAttempt = attemptOf(reproducing);
    var seeking = startPlaying(worker);

    seek(worker, seeking);

    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());
    storeSegments(reproducing, 41, 41);
    seek(worker, seeking, 200);
    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactlyInAnyOrder(steady.job().getJobAttemptId(), reproducingAttempt);
  }

  @Test
  @DisplayName(
      "Should never name a job attempt whose own output has reached media stored before it started")
  void shouldNeverNameAJobAttemptWhoseOwnOutputHasReachedMediaStoredBeforeItStarted()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    var reaching = startPlaying(worker);
    storeSegments(reaching, 31, 35);
    assertThat(storeAndAwait(seek(worker, reaching, 20))).isInstanceOf(SegmentDelivery.Ready.class);
    storeSegments(reaching, 21, 30);
    var seeking = startPlaying(worker);

    seek(worker, seeking);

    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());
  }

  @Test
  @DisplayName(
      "Should never name an on-demand job attempt that reproduces media stored before it started,"
          + " and name it once it stores media past it")
  void
      shouldNeverNameAnOnDemandJobAttemptThatReproducesMediaStoredBeforeItStartedAndNameItOncePast()
          throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    var switching = startPlaying(worker);
    storeSegments(switching, "720p", 21, 40);
    var switched = request(switching.sessionId(), "720p", segmentName("720p", 20));
    assertThat(worker.nextJobs(1).getFirst().getVariant().getVariantLabel()).isEqualTo("720p");
    assertThat(storeAndAwait(switched)).isInstanceOf(SegmentDelivery.Ready.class);
    var switchedAttempt = attemptOf(switching, "720p");
    var seeking = startPlaying(worker);

    seek(worker, seeking);

    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());
    storeSegments(switching, "720p", 41, 41);
    seek(worker, seeking, 200);
    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactlyInAnyOrder(steady.job().getJobAttemptId(), switchedAttempt);
  }

  @Test
  @DisplayName(
      "Should never name a job attempt for media that a disconnected worker's upload stored after"
          + " the job attempt started")
  void shouldNeverNameAJobAttemptForMediaThatADisconnectedWorkersUploadStoredAfterItStarted()
      throws Exception {
    var departing = registerWorker(WORKER_ID, 8);
    var replaced = startPlaying(departing);
    storeSegments(replaced, 11, 40);
    departing.reportStarted(replaced.job());
    segmentStore.pauseNextPublication(PublicationPause.BEFORE_THE_SEGMENT_IS_READABLE);
    var lateUpload = departing.uploadMediaSegment(replaced.job(), 41);
    segmentStore.awaitPausedPublication();
    var remaining = registerWorker(OTHER_WORKER_ID, 8);
    departing.close();
    await()
        .atMost(REQUEST_LIMIT)
        .until(() -> !transcodeExecutor.isRunning(replaced.sessionId(), "480p"));
    assertThat(storeAndAwait(seek(remaining, replaced, 10)))
        .isInstanceOf(SegmentDelivery.Ready.class);
    segmentStore.releasePublication();
    lateUpload.handle((_, _) -> null).get(REQUEST_LIMIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(storedSegments.segmentExists(replaced.sessionId(), segmentName(41)))
        .as("the authorized upload stored its segment after the replacement started")
        .isTrue();
    var steady = startPlaying(remaining);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    var seeking = startPlaying(remaining);

    seek(remaining, seeking);

    assertThat(nextYield(remaining).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());
  }

  @Test
  @DisplayName(
      "Should keep the yield of a backward seek when the earlier seek's older request delivers its"
          + " segment")
  void shouldKeepTheYieldOfABackwardSeekWhenTheEarlierSeeksOlderRequestDeliversItsSegment()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    var seeking = startPlaying(worker);
    var earlierSeek = seek(worker, seeking);
    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());
    // Once the earlier request waits for its next wake, it reads its segment, stored without a
    // wake, only at the pass that the backward relocation's stop starts.
    await().atMost(REQUEST_LIMIT).until(earlierSeek.delivery()::isAwaitingALatchWithDeadline);
    storeAsUpload(seeking.sessionId(), "480p", SEEK_SEGMENT);

    seek(worker, seeking, 50);

    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());
    assertThat(earlierSeek.delivery().await(REQUEST_LIMIT))
        .isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(yieldsUntilTheSteadyAttemptStops(worker, steady))
        .allSatisfy(
            yieldCommand ->
                assertThat(yieldCommand.getJobAttemptIdsList())
                    .containsExactly(steady.job().getJobAttemptId()));
  }

  @Test
  @DisplayName(
      "Should end the yield when the seeking stream session switches to another variant before"
          + " the seek's first segment arrives")
  void
      shouldEndTheYieldWhenTheSeekingStreamSessionSwitchesToAnotherVariantBeforeTheSeeksFirstSegmentArrives()
          throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    var seeking = startPlaying(worker);
    seek(worker, seeking);
    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());
    var switched = request(seeking.sessionId(), "720p", "720p/segment" + SEEK_SEGMENT + ".m4s");
    assertThat(worker.nextJobs(1).getFirst().getVariant().getVariantLabel()).isEqualTo("720p");

    // The delivered 720p segment completes the switch, which stops the seek's 480p variant.
    assertThat(storeAndAwait(switched)).isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(nextYield(worker).getJobAttemptIdsList()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should place a viewer at its highest requested segment when it lies past the viewer's"
          + " reported position")
  void shouldPlaceAViewerAtItsHighestRequestedSegmentWhenItLiesPastTheReportedPosition()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    var skipping = startPlaying(worker);
    deliverAhead(skipping, 9);
    registry.updatePlaybackState(skipping.sessionId(), 0, PlaybackState.PLAYING);
    // A forward seek within the stored media relocates nothing, and its report is still to come.
    assertThat(request(skipping.sessionId(), segmentName(8)).delivery().await(REQUEST_LIMIT))
        .isInstanceOf(SegmentDelivery.Ready.class);
    var seeking = startPlaying(worker);

    seek(worker, seeking);

    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());
  }

  @Test
  @DisplayName(
      "Should resume a named job attempt as soon as its player's request within stored media"
          + " takes its lead")
  void shouldResumeANamedJobAttemptAsSoonAsItsPlayersRequestWithinStoredMediaTakesItsLead()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 9);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    var seeking = startPlaying(worker);
    seek(worker, seeking);
    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());

    assertThat(request(steady.sessionId(), segmentName(8)).delivery().await(REQUEST_LIMIT))
        .isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(nextYield(worker).getJobAttemptIdsList()).isEmpty();
  }

  @Test
  @DisplayName("Should advance a playing viewer's reported position with the clock")
  void shouldAdvanceAPlayingViewersReportedPositionWithTheClock() throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 7);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    // 24 s later the viewer plays segment 4, three segments behind the last delivered one.
    clock.advance(Duration.ofSeconds(24));
    var seeking = startPlaying(worker);

    var seek = seek(worker, seeking);
    assertThat(storeAndAwait(seek)).isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(yieldsUntilTheSteadyAttemptStops(worker, steady)).isEmpty();
  }

  @Test
  @DisplayName("Should keep a paused viewer at its reported position as the clock advances")
  void shouldKeepAPausedViewerAtItsReportedPositionAsTheClockAdvances() throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 7);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PAUSED);
    clock.advance(Duration.ofSeconds(24));
    var seeking = startPlaying(worker);

    seek(worker, seeking);

    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());
  }

  @Test
  @DisplayName(
      "Should measure the lead from the highest requested media segment when the timeline report"
          + " is older than 30 seconds")
  void
      shouldMeasureTheLeadFromTheHighestRequestedMediaSegmentWhenTheTimelineReportIsOlderThan30Seconds()
          throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    var steady = startPlaying(worker);
    deliverAhead(steady, 8);
    // Extrapolated, the report would place the viewer in segment 5, three segments behind.
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    clock.advance(Duration.ofSeconds(31));
    var seeking = startPlaying(worker);

    seek(worker, seeking);

    assertThat(nextYield(worker).getJobAttemptIdsList())
        .containsExactly(steady.job().getJobAttemptId());
  }

  @Test
  @DisplayName(
      "Should never name a job attempt whose next segment outlasts the media stored ahead of its"
          + " viewer less the hold limit")
  void shouldNeverNameAJobAttemptWhoseNextSegmentOutlastsTheMediaStoredAheadOfItsViewer()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    probeResults.setDefaultProbe(
        defaultProbeBuilder().keyframes(keyframesWithAMergedSegment()).build());
    var steady = startPlayingTheFirstVariant(worker);
    // Segments 1 to 4 hold 24 s, and the next one, segment 5, runs on to the keyframe at 120 s.
    deliverAhead(steady, 4);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    probeResults.setDefaultProbe(encodedProbe());
    var seeking = startPlaying(worker);

    var seek = seek(worker, seeking);
    assertThat(storeAndAwait(seek)).isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(yieldsUntilTheSteadyAttemptStops(worker, steady)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should never name a job attempt whose lead covers its next segment but not a long merged"
          + " segment after it")
  void shouldNeverNameAJobAttemptWhoseLeadCoversItsNextSegmentButNotALongMergedSegmentAfterIt()
      throws Exception {
    var worker = registerWorker(WORKER_ID, 8);
    probeResults.setDefaultProbe(
        defaultProbeBuilder().keyframes(keyframesWithAMergedSegmentFrom(42, 132)).build());
    var steady = startPlayingTheFirstVariant(worker);
    // Segments 1 to 5 hold 30 s, enough for the next 6 s segment; segment 7 runs from 42 s to the
    // keyframe at 132 s, so a run of seeks could pause the job attempt until it comes too late.
    deliverAhead(steady, 5);
    registry.updatePlaybackState(steady.sessionId(), 0, PlaybackState.PLAYING);
    probeResults.setDefaultProbe(encodedProbe());
    var seeking = startPlaying(worker);

    var seek = seek(worker, seeking);
    assertThat(storeAndAwait(seek)).isInstanceOf(SegmentDelivery.Ready.class);

    assertThat(yieldsUntilTheSteadyAttemptStops(worker, steady)).isEmpty();
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

  // An HEVC source that every variant encodes.
  private static MediaProbe encodedProbe() {
    return defaultProbeBuilder()
        .videoCodec("hevc")
        .framerate(OptionalDouble.of(23.976))
        .bitrate(8_000_000L)
        .build();
  }

  // Keyframes every 6 s up to 30 s, then none until 120 s and every 6 s after it, so that a stream
  // copy's segment from 30 s merges the slots up to 120 s.
  private static KeyframeKnowledge keyframesWithAMergedSegment() {
    return keyframesWithAMergedSegmentFrom(30, 120);
  }

  // Keyframes every 6 s up to the merge's start, then none until its end and every 6 s after it.
  private static KeyframeKnowledge keyframesWithAMergedSegmentFrom(long start, long end) {
    var seconds =
        LongStream.concat(
            LongStream.iterate(0, second -> second <= start, second -> second + 6),
            LongStream.iterate(end, second -> second <= 7200, second -> second + 6));
    return new KeyframeKnowledge.Known(
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(1)
            .source(KeyframePositionSource.CONTAINER_INDEX)
            .keyframes(seconds.mapToObj(second -> new KeyframePosition(second, second)).toList())
            .build());
  }

  // A stream session whose player has received the first media segment of its first variant.
  private Playing startPlayingTheFirstVariant(LoopbackWorker worker) throws Exception {
    var options = StreamingOptions.builder().quality(VideoQuality.AUTO).build();
    var session =
        accepted(
            streamingService.createSession(
                createStreamSessionCommand(mediaFile.getId(), UUID.randomUUID(), options)));
    var job = worker.nextJobs(1).getFirst();
    var playing = new Playing(session.getSessionId(), job);
    var label = job.getVariant().getVariantLabel();
    assertThat(storeAndAwait(request(playing.sessionId(), label, segmentName(label, 0))))
        .isInstanceOf(SegmentDelivery.Ready.class);
    return playing;
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

  // Requests the seek's first segment and waits until the relocation has started the seek's job
  // attempt, so that the test stores that segment only after the seek began.
  private PendingRequest seek(LoopbackWorker worker, Playing seeking) throws InterruptedException {
    return seek(worker, seeking, SEEK_SEGMENT);
  }

  private PendingRequest seek(LoopbackWorker worker, Playing seeking, int segmentIndex)
      throws InterruptedException {
    var seek = request(seeking.sessionId(), segmentName(segmentIndex));
    var response = worker.nextResponse();
    while (!response.hasStartVariant()) {
      if (response.hasYieldToSeek()) {
        unreadYields.add(response.getYieldToSeek());
      }

      response = worker.nextResponse();
    }

    assertThat(response.getStartVariant().getJob().getExecution().getStartSequenceNumber())
        .isEqualTo(segmentIndex);
    return seek;
  }

  private void storeSegments(Playing playing, int firstSegment, int lastSegment) {
    storeSegments(playing, "480p", firstSegment, lastSegment);
  }

  private void storeSegments(
      Playing playing, String variantLabel, int firstSegment, int lastSegment) {
    for (var index = firstSegment; index <= lastSegment; index++) {
      storeAsUpload(playing.sessionId(), variantLabel, index);
    }
  }

  private Uuid attemptOf(Playing playing) {
    return attemptOf(playing, "480p");
  }

  private Uuid attemptOf(Playing playing, String variantLabel) {
    var session = registry.findById(playing.sessionId()).orElseThrow();
    return ProtoUuid.toProto(session.getVariantHandle(variantLabel).orElseThrow().attemptId());
  }

  private void deliverAhead(Playing playing, int lastSegment) {
    var label = playing.job().getVariant().getVariantLabel();
    for (var index = 0; index <= lastSegment; index++) {
      storeAsUpload(playing.sessionId(), label, index);
    }
  }

  // The yields that reach the worker before the stop that the test sends after the seek, which
  // the worker session carries after any yield that the seek sent.
  private List<YieldToSeekCommand> yieldsUntilTheSteadyAttemptStops(
      LoopbackWorker worker, Playing steady) throws InterruptedException {
    server.stopVariant(steady.sessionId(), steady.job().getVariant().getVariantLabel());
    var yields = new ArrayList<>(unreadYields);
    unreadYields.clear();
    worker
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
        .forEach(yields::add);
    return yields;
  }

  private YieldToSeekCommand nextYield(LoopbackWorker worker) throws InterruptedException {
    if (!unreadYields.isEmpty()) {
      return unreadYields.poll();
    }

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
    return segmentName("480p", index);
  }

  private static String segmentName(String variantLabel, int index) {
    return SegmentNames.storedSegmentName(variantLabel, SegmentNames.mediaSegmentName(index));
  }

  private PendingRequest request(UUID sessionId, String segmentName) {
    return request(sessionId, "480p", segmentName);
  }

  private PendingRequest request(UUID sessionId, String variantLabel, String segmentName) {
    var delivery =
        BoundedTask.start(() -> coordinator.deliver(sessionId, variantLabel, segmentName));
    requests.add(delivery);
    return new PendingRequest(sessionId, variantLabel, segmentName, delivery);
  }

  // The loopback worker uploads nothing, so the test stores each segment and wakes the requests
  // held on its variant, as a stored upload does.
  private SegmentDelivery storeAndAwait(PendingRequest pending) throws Exception {
    storeAsUpload(
        pending.sessionId(),
        pending.variantLabel(),
        SegmentNames.indexOf(pending.segmentName()).orElseThrow());
    server.heldSegmentRequests().wakeVariant(pending.sessionId(), pending.variantLabel());
    return pending.delivery().await(REQUEST_LIMIT);
  }

  // Stores the media segment as an upload of the variant's running job attempt does, which credits
  // the segment to that job attempt.
  private void storeAsUpload(UUID sessionId, String variantLabel, int index) {
    storedSegments.addSegment(sessionId, segmentName(variantLabel, index), SEGMENT_BYTES);
    var target = transcodeExecutor.runningExecutionTarget(sessionId, variantLabel);
    registry
        .findById(sessionId)
        .flatMap(session -> session.getVariantHandle(variantLabel))
        .filter(_ -> target.isPresent())
        .ifPresent(
            handle ->
                publications.onMediaSegmentPublished(
                    MediaSegmentPublishedEvent.builder()
                        .streamSessionId(sessionId)
                        .jobAttemptId(handle.attemptId())
                        .variantLabel(variantLabel)
                        .segmentIndex(index)
                        .executionTarget(target.get())
                        .build()));
  }

  // A real upload's publication reaches seek priority as the application's event does.
  private void forwardPublication(Object event) {
    if (event instanceof MediaSegmentPublishedEvent published) {
      publications.onMediaSegmentPublished(published);
    }
  }

  private record Playing(UUID sessionId, VariantJob job) {}

  private record PendingRequest(
      UUID sessionId,
      String variantLabel,
      String segmentName,
      BoundedTask<SegmentDelivery> delivery) {}
}
