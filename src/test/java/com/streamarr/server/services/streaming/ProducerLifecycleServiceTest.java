package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.SegmentRequestFixture.earliestArrivingSegmentRequestBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.abrSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultLadderSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.mintHandle;
import static com.streamarr.server.fixtures.StreamSessionFixture.sessionWithDurationBuilder;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.h264Nvenc;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.libx264;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.libx264NotKeyframeVerified;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeServerSettingsRepository;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.LongStream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Producer Lifecycle Service Tests")
class ProducerLifecycleServiceTest {

  private static final ExecutionTargetId LIBX264_WORKER = new ExecutionTargetId("libx264-worker");
  private static final ExecutionTargetId NVENC_WORKER = new ExecutionTargetId("nvenc-worker");
  private static final ExecutionTargetId UNVERIFIED_LIBX264_WORKER =
      new ExecutionTargetId("unverified-libx264-worker");

  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private ProducerLifecycleService lifecycle;
  private MutableClock clock;
  private final AuthenticatedIdentity serverAdmin = accountScopedBuilder().build();
  private ServerSettingsService serverSettings;

  @BeforeEach
  void setUp() {
    clock = new MutableClock();
    serverSettings =
        new ServerSettingsService(
            new FakeAuthorizationService(serverAdmin), new FakeServerSettingsRepository());
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    lifecycle =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(
                StreamingProperties.builder()
                    .maxConcurrentTranscodes(3)
                    .targetSegmentDuration(Duration.ofSeconds(6))
                    .sessionTimeout(Duration.ofSeconds(60))
                    .build())
            .clock(clock)
            .runtimeRegistry(runtimeRegistry)
            .serverSettings(serverSettings)
            .build()
            .lifecycle();
  }

  @ParameterizedTest
  @CsvSource({"60, 1, 60.000", "60000, 1001, 59.940"})
  @DisplayName("Should use the chosen exact output rate when legacy probe timing disagrees")
  void shouldUseTheChosenExactOutputRateWhenLegacyProbeTimingDisagrees(
      int numerator, int denominator, String playlistRate) {
    var rate = new Fraction(numerator, denominator);
    var source =
        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
            .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(rate))
            .build();
    var session =
        defaultSessionBuilder()
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                    .videoCodecFamily("h264")
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .videoSource(Optional.of(source))
                    .build())
            .build();
    runtimeRegistry.save(session);
    var playlists = new HlsPlaylistService(StreamingProperties.builder().build());

    lifecycle.startFirstListedVariant(session);
    var playlist = playlists.generateMultivariantPlaylist(session, "test-token");
    var requests = transcodeExecutor.getStartedRequests();

    assertThat(requests).hasSize(1);
    SoftAssertions.assertSoftly(
        softly -> {
          softly.assertThat(playlist).contains(",FRAME-RATE=" + playlistRate);
          softly.assertThat(playlist).contains("avc1.64002A");
          softly.assertThat(requests.getFirst().framerate()).hasValue(rate.toDouble());
          softly
              .assertThat(requests.getFirst().videoEncodeDecision().orElseThrow().codecString())
              .isEqualTo("avc1.64002A");
        });
  }

  @Test
  @DisplayName("Should refuse initial dispatch when the session is no longer registered")
  void shouldRefuseInitialDispatchWhenTheSessionIsNoLongerRegistered() {
    var session = defaultSessionBuilder().build();

    assertThatThrownBy(() -> lifecycle.startFirstListedVariant(session))
        .isInstanceOf(SessionNotFoundException.class);
    assertThat(transcodeExecutor.getStartedRequests()).isEmpty();
  }

  private StreamSession startedSession() {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    return session;
  }

  private StreamSession startedLadderSession() {
    var session = defaultLadderSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    return session;
  }

  private StreamSession midTimelineSession() {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    session.setCurrentSegment(900);
    lifecycle.startFirstListedVariant(session);
    return session;
  }

  private void suspendHandle(StreamSession session) {
    session.setHandle(mintHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());
  }

  @Test
  @DisplayName("Should keep previously transcoded segments when relocating")
  void shouldKeepPreviouslyTranscodedSegmentsWhenRelocating() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment0.m4s", new byte[] {1});
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment100.m4s");

    // Segments are addressed on the absolute timeline, so earlier segments stay valid.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore + 1);
    assertThat(segmentStore.readSegment(session.getSessionId(), "segment0.m4s")).containsExactly(1);
  }

  @Test
  @DisplayName("Should not restart FFmpeg when session is actively transcoding")
  void shouldNotRestartFfmpegWhenSessionIsActivelyTranscoding() {
    var session = startedSession();
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment0.m4s");

    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startedBefore);
  }

  @Test
  @DisplayName("Should not restart FFmpeg when segment already exists on disk")
  void shouldNotRestartFfmpegWhenSegmentAlreadyExistsOnDisk() {
    var session = startedSession();
    suspendHandle(session);
    segmentStore.addSegment(session.getSessionId(), "segment5.m4s", new byte[] {0x47});

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment5.m4s");

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.SUSPENDED);
  }

  @Test
  @DisplayName("Should not throw when positioning nonexistent session")
  void shouldNotThrowWhenPositioningNonexistentSession() {
    assertThatNoException()
        .isThrownBy(
            () ->
                lifecycle.ensurePositioned(
                    UUID.randomUUID(), StreamSession.defaultVariant(), "segment0.m4s"));
  }

  @Test
  @DisplayName("Should update last accessed time when resuming suspended session")
  void shouldUpdateLastAccessedTimeWhenResumingSuspendedSession() {
    var session = startedSession();
    suspendHandle(session);
    session.setLastAccessedAt(Instant.now().minusSeconds(200));
    var oldAccessTime = session.getLastAccessedAt();

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment5.m4s");

    assertThat(session.getLastAccessedAt()).isAfter(oldAccessTime);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @ParameterizedTest(name = "{0} → startNumber={1}, seek={2}")
  @DisplayName("Should resume with correct start number when segment name encodes an index")
  @CsvSource({
    "segment0.m4s, 0, 0",
    "segment5.m4s, 5, 30",
    "segment12.m4s, 12, 72",
    "720p/segment3.m4s, 3, 18"
  })
  void shouldResumeWithCorrectStartNumberWhenSegmentNameEncodesIndex(
      String segmentName, int startNumber, int seekPosition) {
    var session = startedSession();
    suspendHandle(session);

    lifecycle.ensurePositioned(session.getSessionId(), StreamSession.defaultVariant(), segmentName);

    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isTrue();
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.startSequenceNumber()).isEqualTo(startNumber);
    assertThat(lastRequest.seekPosition()).isEqualTo(seekPosition);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(2);
  }

  @Test
  @DisplayName("Should not relocate a mid-timeline producer when an init segment is requested")
  void shouldNotRelocateMidTimelineProducerWhenInitSegmentIsRequested() {
    var session = midTimelineSession();
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), StreamSession.defaultVariant(), "init.mp4");

    // init.mp4 carries no index; it must never be read as segment 0 and drag the run to the top.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startedBefore);
  }

  @Test
  @DisplayName("Should resume a mid-timeline session at its own start when an init is requested")
  void shouldResumeMidTimelineSessionAtItsOwnStartWhenInitIsRequested() {
    var session = midTimelineSession();
    session.setHandle(session.getHandle().orElseThrow().withStatus(TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());

    lifecycle.ensurePositioned(session.getSessionId(), StreamSession.defaultVariant(), "init.mp4");

    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.startSequenceNumber()).isEqualTo(900);
    assertThat(lastRequest.seekPosition()).isEqualTo(5400);
    assertThat(session.getHandle().orElseThrow().startSequenceNumber()).isEqualTo(900);
  }

  @Test
  @DisplayName("Should resume at beginning when segment name has no index")
  void shouldResumeAtBeginningWhenSegmentNameHasNoIndex() {
    var session = startedSession();
    suspendHandle(session);

    lifecycle.ensurePositioned(session.getSessionId(), StreamSession.defaultVariant(), "init.mp4");

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.startSequenceNumber()).isZero();
    assertThat(lastRequest.seekPosition()).isZero();
  }

  @Test
  @DisplayName(
      "Should resume only the requested variant when a suspended stream session with a ladder is resumed")
  void shouldResumeOnlyTheRequestedVariantWhenASuspendedStreamSessionWithALadderIsResumed() {
    var session = startedLadderSession();
    var suspendedAttempt = session.getVariantHandle("480p").orElseThrow().attemptId();
    lifecycle.suspend(session.getSessionId());
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "480p", "480p/segment5.m4s");

    assertThat(startedSince(startedBefore))
        .singleElement()
        .satisfies(
            resumed -> {
              assertThat(resumed.variantLabel()).isEqualTo("480p");
              assertThat(resumed.startSequenceNumber()).isEqualTo(5);
              assertThat(resumed.seekPosition()).isEqualTo(30);
              assertThat(resumed.attemptId()).isNotEqualTo(suspendedAttempt);
            });
    assertThat(session.getVariantHandles()).containsOnlyKeys("480p");
    assertThat(session.getVariantHandle("480p").orElseThrow().status())
        .isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should start nothing when positioning a variant that has no job attempt")
  void shouldStartNothingWhenPositioningAVariantThatHasNoJobAttempt() {
    var session = startedLadderSession();
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "720p", "720p/segment100.m4s");

    assertThat(startedSince(startedBefore)).isEmpty();
    assertThat(session.getVariantHandle("720p")).isEmpty();
  }

  @Test
  @DisplayName(
      "Should start nothing on demand when the requested media segment lies past the advertised timeline")
  void shouldStartNothingOnDemandWhenTheRequestedMediaSegmentLiesPastTheAdvertisedTimeline() {
    var session =
        defaultLadderSessionBuilder()
            .mediaProbe(defaultProbeBuilder().duration(Duration.ofSeconds(60)).build())
            .build();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.prepareRequestedVariant(session.getSessionId(), "720p", "720p/segment10.m4s");

    assertThat(startedSince(startedBefore)).isEmpty();
    assertThat(session.getVariantHandle("720p")).isEmpty();
  }

  @Test
  @DisplayName(
      "Should start a remux stream session's variant on demand when transcodes fill the transcode limit")
  void shouldStartARemuxStreamSessionsVariantOnDemandWhenTranscodesFillTheTranscodeLimit() {
    startedLadderSession();
    startedLadderSession();
    startedLadderSession();
    var remuxSession = defaultSessionBuilder().build();
    runtimeRegistry.save(remuxSession);

    lifecycle.prepareRequestedVariant(
        remuxSession.getSessionId(), StreamSession.defaultVariant(), "segment4.m4s");

    assertThat(transcodeExecutor.getStartedRequests().getLast().startSequenceNumber()).isEqualTo(4);
    assertThat(remuxSession.getVariantHandle(StreamSession.defaultVariant())).isPresent();
  }

  @Test
  @DisplayName(
      "Should stop no video variant when a media segment of a variant that the player has since left is delivered")
  void shouldStopNoVideoVariantWhenAMediaSegmentOfAVariantThatThePlayerHasSinceLeftIsDelivered() {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    var played = lifecycle.prepareRequestedVariant(sessionId, "480p", "480p/segment0.m4s");
    lifecycle.completeVariantSwitch(played);
    lifecycle.prepareRequestedVariant(sessionId, "1080p", "1080p/segment1.m4s");

    lifecycle.completeVariantSwitch(played);

    assertThat(session.getVariantHandles()).containsOnlyKeys("480p", "1080p");
  }

  @Test
  @DisplayName(
      "Should stop only the stream session's own variant that the player leaves when the transcode limit leaves no room for the switched variant")
  void
      shouldStopOnlyTheStreamSessionsOwnVariantThatThePlayerLeavesWhenTheTranscodeLimitLeavesNoRoomForTheSwitchedVariant() {
    var others = List.of(startedLadderSession(), startedLadderSession());
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    var played = lifecycle.prepareRequestedVariant(sessionId, "480p", "480p/segment0.m4s");
    lifecycle.completeVariantSwitch(played);

    lifecycle.prepareRequestedVariant(sessionId, "1080p", "1080p/init.mp4");
    assertThat(session.getVariantHandles()).containsOnlyKeys("480p");
    lifecycle.prepareRequestedVariant(sessionId, "1080p", "1080p/segment1.m4s");

    assertThat(session.getVariantHandles()).containsOnlyKeys("1080p");
    assertThat(others)
        .allSatisfy(other -> assertThat(other.getVariantHandles()).containsOnlyKeys("480p"));
  }

  @Test
  @DisplayName(
      "Should keep the stream session's own left variant when the worker that it runs on cannot run the switched variant's pinned encoder")
  void
      shouldKeepTheStreamSessionsOwnLeftVariantWhenTheWorkerThatItRunsOnCannotRunTheSwitchedVariantsPinnedEncoder() {
    transcodeExecutor.setTargetEncoders(LIBX264_WORKER, List.of(libx264()));
    transcodeExecutor.setTargetEncoders(NVENC_WORKER, List.of(h264Nvenc()));
    transcodeExecutor.setExecutionTargets(List.of(LIBX264_WORKER, NVENC_WORKER));
    transcodeExecutor.limitJobSlots(LIBX264_WORKER, 1);
    transcodeExecutor.limitJobSlots(NVENC_WORKER, 1);
    var session = defaultLadderSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    var played = lifecycle.prepareRequestedVariant(sessionId, "480p", "480p/segment0.m4s");
    lifecycle.completeVariantSwitch(played);
    var switchedUp = lifecycle.prepareRequestedVariant(sessionId, "1080p", "1080p/segment1.m4s");
    lifecycle.completeVariantSwitch(switchedUp);
    startedLadderSession();

    lifecycle.prepareRequestedVariant(sessionId, "480p", "480p/segment2.m4s");

    assertThat(session.getVariantHandles()).containsOnlyKeys("1080p");
    assertThat(transcodeExecutor.runningExecutionTarget(sessionId, "1080p")).contains(NVENC_WORKER);
  }

  private ProducerLifecycleService lifecycleWith(
      MutexFactory<UUID> sessionMutex, int maxConcurrentTranscodes) {
    return StreamingRigFixture.streamingRigBuilder()
        .transcodeExecutor(transcodeExecutor)
        .segmentStore(segmentStore)
        .properties(
            StreamingProperties.builder()
                .maxConcurrentTranscodes(maxConcurrentTranscodes)
                .targetSegmentDuration(Duration.ofSeconds(6))
                .sessionTimeout(Duration.ofSeconds(60))
                .build())
        .clock(clock)
        .runtimeRegistry(runtimeRegistry)
        .serverSettings(serverSettings)
        .sessionMutex(sessionMutex)
        .build()
        .lifecycle();
  }

  // Plays 480p, then requests 1080p, so the stream session keeps 480p as its left variant beside
  // the running 1080p.
  private StreamSession switchingLadderSession(ProducerLifecycleService lifecycle) {
    var session = defaultLadderSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    var played = lifecycle.prepareRequestedVariant(sessionId, "480p", "480p/segment0.m4s");
    lifecycle.completeVariantSwitch(played);
    lifecycle.prepareRequestedVariant(sessionId, "1080p", "1080p/segment1.m4s");
    assertThat(session.getVariantHandles()).containsOnlyKeys("480p", "1080p");
    return session;
  }

  @Test
  @DisplayName(
      "Should stop each one's left variant without a deadlock when two stream sessions that keep a left variant look for room in each other at once")
  void
      shouldStopEachOnesLeftVariantWithoutADeadlockWhenTwoStreamSessionsThatKeepALeftVariantLookForRoomInEachOtherAtOnce()
          throws Exception {
    var sessionMutex = new ParkingMutexFactory(2);
    var parkingLifecycle = lifecycleWith(sessionMutex, 4);
    var first = switchingLadderSession(parkingLifecycle);
    var second = switchingLadderSession(parkingLifecycle);
    assertThat(parkingLifecycle.freeConfiguredTranscodeSlots()).isZero();
    var stoppedBefore = transcodeExecutor.getStoppedVariants().size();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var firstLooks =
          executor.submit(
              () ->
                  sessionMutex.parkAfterTakingAnotherMutex(
                      () ->
                          parkingLifecycle.stopLeftVariantOfAnotherStreamSession(
                              first.getSessionId(), first.getTranscodeDecision())));
      var secondLooks =
          executor.submit(
              () ->
                  sessionMutex.parkAfterTakingAnotherMutex(
                      () ->
                          parkingLifecycle.stopLeftVariantOfAnotherStreamSession(
                              second.getSessionId(), second.getTranscodeDecision())));
      sessionMutex.awaitParked();
      sessionMutex.release();

      firstLooks.get(2, TimeUnit.SECONDS);
      secondLooks.get(2, TimeUnit.SECONDS);
    }

    var stopped = transcodeExecutor.getStoppedVariants();
    assertThat(stopped.subList(stoppedBefore, stopped.size()))
        .containsExactlyInAnyOrder(first.getSessionId() + "/480p", second.getSessionId() + "/480p");
    assertThat(first.getVariantHandles()).containsOnlyKeys("1080p");
    assertThat(second.getVariantHandles()).containsOnlyKeys("1080p");
  }

  @Test
  @DisplayName(
      "Should stop another stream session's left variant to start the first-listed variant when no worker job slot is free")
  void
      shouldStopAnotherStreamSessionsLeftVariantToStartTheFirstListedVariantWhenNoWorkerJobSlotIsFree() {
    var switching = switchingLadderSession(lifecycle);
    transcodeExecutor.limitJobSlots(2);
    var session = defaultLadderSessionBuilder().build();
    runtimeRegistry.save(session);

    lifecycle.startFirstListedVariant(session);

    assertThat(session.getVariantHandles()).containsOnlyKeys("480p");
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), "480p")).isTrue();
    assertThat(switching.getVariantHandles()).containsOnlyKeys("1080p");
  }

  @Test
  @DisplayName(
      "Should refuse to look for another stream session's left variant when the caller holds a stream session mutex")
  void
      shouldRefuseToLookForAnotherStreamSessionsLeftVariantWhenTheCallerHoldsAStreamSessionMutex() {
    var sessionMutex = new MutexFactory<UUID>();
    var guardedLifecycle = lifecycleWith(sessionMutex, 3);
    var session = switchingLadderSession(guardedLifecycle);
    var sessionId = session.getSessionId();
    var held = sessionMutex.getMutex(sessionId);

    held.lock();
    try {
      assertThatThrownBy(
              () ->
                  guardedLifecycle.stopLeftVariantOfAnotherStreamSession(
                      UUID.randomUUID(), session.getTranscodeDecision()))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      held.unlock();
    }

    assertThat(session.getVariantHandles()).containsOnlyKeys("480p", "1080p");
  }

  /**
   * Parks each registered thread right after it takes a stream session mutex that it tried to take
   * without waiting, until the test releases every parked thread.
   */
  private static final class ParkingMutexFactory extends MutexFactory<UUID> {

    private final Map<UUID, ReentrantLock> mutexes = new ConcurrentHashMap<>();
    private final Set<Thread> parkingThreads = ConcurrentHashMap.newKeySet();
    private final CountDownLatch parked;
    private final CountDownLatch released = new CountDownLatch(1);

    private ParkingMutexFactory(int parkedThreads) {
      parked = new CountDownLatch(parkedThreads);
    }

    @Override
    public ReentrantLock getMutex(UUID sessionId) {
      return mutexes.computeIfAbsent(sessionId, _ -> new ParkingLock());
    }

    @Override
    public boolean isAnyMutexHeldByCurrentThread() {
      return mutexes.values().stream().anyMatch(ReentrantLock::isHeldByCurrentThread);
    }

    private Void parkAfterTakingAnotherMutex(Runnable action) {
      parkingThreads.add(Thread.currentThread());
      action.run();
      return null;
    }

    private void awaitParked() throws InterruptedException {
      assertThat(parked.await(5, TimeUnit.SECONDS))
          .as("each thread must take another stream session's mutex")
          .isTrue();
    }

    private void release() {
      released.countDown();
    }

    private final class ParkingLock extends ReentrantLock {

      @Override
      public boolean tryLock() {
        var taken = super.tryLock();
        if (taken && parkingThreads.remove(Thread.currentThread())) {
          parked.countDown();
          awaitRelease();
        }

        return taken;
      }

      private void awaitRelease() {
        try {
          assertThat(released.await(5, TimeUnit.SECONDS))
              .as("the test must release the parked threads")
              .isTrue();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Interrupted while parked at a stream session mutex", e);
        }
      }
    }
  }

  @Test
  @DisplayName(
      "Should start nothing when positioning a variant that the stream session does not offer")
  void shouldStartNothingWhenPositioningAVariantThatTheStreamSessionDoesNotOffer() {
    var session = startedLadderSession();
    lifecycle.suspend(session.getSessionId());
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "2160p", "2160p/segment5.m4s");

    assertThat(startedSince(startedBefore)).isEmpty();
    assertThat(session.isSuspended()).isTrue();
  }

  @Test
  @DisplayName(
      "Should relocate the transcode when the requested segment is behind the encoder start")
  void shouldRelocateTheTranscodeWhenTheRequestedSegmentIsBehindTheEncoderStart() {
    var session = startedSession();
    // Move the encoder forward first: segment50 is far ahead of fresh output.
    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment50.m4s");

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment10.m4s");

    // The encoder started at segment50 and will never produce segment10.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(3);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.seekPosition()).isEqualTo(60);
    assertThat(lastRequest.startSequenceNumber()).isEqualTo(10);
  }

  @Test
  @DisplayName("Should relocate the transcode when the requested segment is far ahead of progress")
  void shouldRelocateTheTranscodeWhenTheRequestedSegmentIsFarAheadOfProgress() {
    var session = startedSession();

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment100.m4s");

    // Nothing near segment100 has been produced; waiting would stall the player.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(2);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.seekPosition()).isEqualTo(600);
    assertThat(lastRequest.startSequenceNumber()).isEqualTo(100);
  }

  @Test
  @DisplayName("Should wait when the requested segment is near the encoder start")
  void shouldWaitWhenTheRequestedSegmentIsNearTheEncoderStart() {
    var session = startedSession();
    var attemptBefore = session.getHandle().orElseThrow().attemptId();
    var requestsBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment2.m4s");

    // The encoder started at segment0 and will reach segment2 shortly.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(requestsBefore);
    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attemptBefore);
  }

  @Test
  @DisplayName("Should wait when the encoder is within the forward gap of the request")
  void shouldWaitWhenTheEncoderIsWithinTheForwardGapOfTheRequest() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment96.m4s", new byte[] {1});
    var attemptBefore = session.getHandle().orElseThrow().attemptId();
    var requestsBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment100.m4s");

    // segment96 exists, so the encoder is close behind the request.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(requestsBefore);
    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attemptBefore);
  }

  @Test
  @DisplayName("Should not relocate when the requested segment already exists")
  void shouldNotRelocateWhenTheRequestedSegmentAlreadyExists() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment10.m4s", new byte[] {1});
    var requestsBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment10.m4s");

    assertThat(transcodeExecutor.getStartedRequests()).hasSize(requestsBefore);
  }

  @Test
  @DisplayName("Should stop producers and mark active handles suspended when suspending")
  void shouldStopProducersAndMarkActiveHandlesSuspendedWhenSuspending() {
    var session = startedSession();

    lifecycle.suspend(session.getSessionId());

    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.SUSPENDED);
  }

  @Test
  @DisplayName("Should not resurrect a destroyed session when suspending it")
  void shouldNotResurrectDestroyedSessionWhenSuspending() {
    var session = startedSession();
    var sessionId = session.getSessionId();
    lifecycle.removeSession(sessionId);

    // The reaper iterates a snapshot, so it can still hold a reference to a destroyed session.
    lifecycle.suspend(session.getSessionId());

    assertThat(runtimeRegistry.findById(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should preserve the attempt identity when suspending an active handle")
  void shouldPreserveTheAttemptIdentityWhenSuspendingAnActiveHandle() {
    var session = startedSession();
    var attemptId = session.getHandle().orElseThrow().attemptId();

    lifecycle.suspend(session.getSessionId());

    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attemptId);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.SUSPENDED);
  }

  private ProducerLifecycleService.RecoveryResult recover(StreamSession session) {
    return lifecycle.recover(
        earliestArrivingSegmentRequestBuilder()
            .sessionId(session.getSessionId())
            .segmentName("segment2.m4s")
            .build());
  }

  private Duration timeUntilStall(StreamSession session) {
    return lifecycle.timeUntilStall(session.getSessionId(), StreamSession.defaultVariant());
  }

  @Test
  @DisplayName(
      "Should bound a held request's wait by the startup stall threshold when recovery tracks no job attempt of the variant")
  void shouldBoundHeldRequestsWaitByStartupStallThresholdWhenRecoveryTracksNoJobAttempt() {
    var session = startedSession();

    // The default 10 s stall threshold plus the 6 s target segment duration.
    assertThat(timeUntilStall(session)).isEqualTo(Duration.ofSeconds(16));
  }

  @ParameterizedTest(name = "{0} at segment {1}")
  @CsvSource({"480p, 2, 16", "480p, 20, 22", "default, 20, 16"})
  @DisplayName(
      "Should give a fresh job attempt one target segment duration for each segment it encodes before its first stored segment")
  void shouldGiveFreshJobAttemptOneTargetSegmentDurationForEachSegmentItEncodesBeforeItsFirstStored(
      String variantLabel, int segmentIndex, long expectedSeconds) {
    var session = variantLabel.equals("480p") ? startedLadderSession() : startedSession();
    var segmentName = SegmentNames.mediaSegmentName(segmentIndex);
    if (!variantLabel.equals(StreamSession.defaultVariant())) {
      segmentName = variantLabel + "/" + segmentName;
    }

    lifecycle.recover(
        earliestArrivingSegmentRequestBuilder()
            .sessionId(session.getSessionId())
            .variantLabel(variantLabel)
            .segmentName(segmentName)
            .build());

    // The default 10 s stall threshold, plus 6 s for the first segment, plus 6 s for the preroll
    // segment that an encoded job attempt starting after segment 0 encodes before it.
    assertThat(lifecycle.timeUntilStall(session.getSessionId(), variantLabel))
        .isEqualTo(Duration.ofSeconds(expectedSeconds));
  }

  @Test
  @DisplayName(
      "Should bound a held request's wait by the time left before the tracked job attempt stalls")
  void shouldBoundHeldRequestsWaitByTimeLeftBeforeTrackedJobAttemptStalls() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment0.m4s", new byte[] {1});
    recover(session);

    clock.advance(Duration.ofSeconds(4));

    // Segment 0 took the attempt out of startup, so the 10 s stall threshold applies.
    assertThat(timeUntilStall(session)).isEqualTo(Duration.ofSeconds(6));
  }

  @Test
  @DisplayName(
      "Should end a held request's wait at once when the tracked job attempt is past its stall deadline")
  void shouldEndHeldRequestsWaitAtOnceWhenTrackedJobAttemptIsPastItsStallDeadline() {
    var session = startedSession();
    recover(session);

    clock.advance(Duration.ofSeconds(20));

    assertThat(timeUntilStall(session)).isZero();
  }

  @Test
  @DisplayName(
      "Should install a fresh attempt at the requested offset when recovering a dead producer")
  void shouldInstallFreshAttemptAtRequestedOffsetWhenRecoveringDeadProducer() {
    var session = startedSession();
    var deadAttempt = session.getHandle().orElseThrow().attemptId();
    transcodeExecutor.markDead(session.getSessionId());

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    var handle = session.getHandle().orElseThrow();
    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(handle.attemptId()).isNotEqualTo(deadAttempt);
    assertThat(handle.startSequenceNumber()).isEqualTo(2);
    var request = transcodeExecutor.getStartedRequests().getLast();
    assertThat(request.seekPosition()).isEqualTo(12);
    assertThat(request.startSequenceNumber()).isEqualTo(2);
    assertThat(request.attemptId()).isEqualTo(handle.attemptId());
  }

  @Test
  @DisplayName(
      "Should advertise the whole timeline's media segment count when installing a replacement attempt")
  void shouldAdvertiseTheWholeTimelineMediaSegmentCountWhenInstallingReplacementAttempt() {
    var sixtySecondSession = sessionWithDurationBuilder(60).build();
    runtimeRegistry.save(sixtySecondSession);
    lifecycle.startFirstListedVariant(sixtySecondSession);
    transcodeExecutor.markDead(sixtySecondSession.getSessionId());

    recover(sixtySecondSession);

    var request = transcodeExecutor.getStartedRequests().getLast();
    assertThat(request.startSequenceNumber()).isEqualTo(2);
    assertThat(request.mediaSegmentCount()).isEqualTo(10);
  }

  @Test
  @DisplayName(
      "Should leave the running producer in place when a request names a media segment past the advertised timeline")
  void shouldLeaveRunningProducerInPlaceWhenRequestNamesMediaSegmentPastTheAdvertisedTimeline() {
    var sixtySecondSession = sessionWithDurationBuilder(60).build();
    runtimeRegistry.save(sixtySecondSession);
    lifecycle.startFirstListedVariant(sixtySecondSession);
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var result =
        lifecycle.recover(
            earliestArrivingSegmentRequestBuilder()
                .sessionId(sixtySecondSession.getSessionId())
                .segmentName("segment10.m4s")
                .build());

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.SEGMENT_NOT_ADVERTISED);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startedBefore);
    assertThat(transcodeExecutor.getStopped()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should install no replacement attempt when a request names a media segment past the advertised timeline")
  void shouldInstallNoReplacementAttemptWhenRequestNamesMediaSegmentPastTheAdvertisedTimeline() {
    var sixtySecondSession = sessionWithDurationBuilder(60).build();
    runtimeRegistry.save(sixtySecondSession);
    lifecycle.startFirstListedVariant(sixtySecondSession);
    transcodeExecutor.markDead(sixtySecondSession.getSessionId());
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var result =
        lifecycle.recover(
            earliestArrivingSegmentRequestBuilder()
                .sessionId(sixtySecondSession.getSessionId())
                .segmentName("segment10.m4s")
                .build());

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.SEGMENT_NOT_ADVERTISED);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startedBefore);
  }

  @Test
  @DisplayName("Should stop and replace the producer when its startup budget expires")
  void shouldStopAndReplaceProducerWhenItsStartupBudgetExpires() {
    var session = startedSession();
    recover(session);
    clock.advance(Duration.ofSeconds(16));

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(ExecutionTargetId.LOCAL);
    assertThat(transcodeExecutor.getStoppedVariants())
        .containsExactly(session.getSessionId() + "/" + StreamSession.defaultVariant());
  }

  @Test
  @DisplayName("Should preserve the current attempt when its producer is healthy")
  void shouldPreserveCurrentAttemptWhenItsProducerIsHealthy() {
    var session = startedSession();
    var attempt = session.getHandle().orElseThrow().attemptId();

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attempt);
    assertThat(transcodeExecutor.getStoppedVariants()).isEmpty();
  }

  @Test
  @DisplayName("Should recover on an execution target when a suspended session cannot resume")
  void shouldRecoverOnExecutionTargetWhenSuspendedSessionCannotResume() {
    var session = startedSession();
    lifecycle.suspend(session.getSessionId());
    transcodeExecutor.failUntargetedStarts();

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(ExecutionTargetId.LOCAL);
  }

  @Test
  @DisplayName("Should mark the copy handle failed when a terminal output mismatch ends recovery")
  void shouldMarkTheCopyHandleFailedWhenATerminalOutputMismatchEndsRecovery() {
    var session = startedSession();
    var attemptId = session.getHandle().orElseThrow().attemptId();
    var startedRequests = transcodeExecutor.getStartedRequests();
    transcodeExecutor.failTerminally(session.getSessionId(), TranscodeFailure.OUTPUT_MISMATCH);

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);

    assertThat(runtimeRegistry.findById(session.getSessionId()).orElseThrow().getHandle())
        .contains(session.getHandle().orElseThrow().withStatus(TranscodeStatus.FAILED));
    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attemptId);
    assertThat(transcodeExecutor.getStartedRequests()).isEqualTo(startedRequests);
    assertThat(transcodeExecutor.terminalFailure(session.getSessionId()))
        .contains(TranscodeFailure.OUTPUT_MISMATCH);
  }

  @Test
  @DisplayName(
      "Should exhaust a suspended session when neither resume nor recovery can start a producer")
  void shouldExhaustSuspendedSessionWhenNeitherResumeNorRecoveryCanStartProducer() {
    var session = startedSession();
    lifecycle.suspend(session.getSessionId());
    transcodeExecutor.failUntargetedStarts();
    transcodeExecutor.refuseTarget(ExecutionTargetId.LOCAL);

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName(
      "Should request the rotated size when a session without a ladder starts an upright phone recording")
  void shouldRequestTheRotatedSizeWhenASessionWithoutALadderStartsAnUprightPhoneRecording() {
    var session =
        defaultSessionBuilder()
            .mediaProbe(
                defaultProbeBuilder()
                    .videoPlaybackFacts(
                        Optional.of(
                            VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                                .streamIndex(0)
                                .rotationDegrees(OptionalInt.of(-90))
                                .build()))
                    .build())
            .build();
    runtimeRegistry.save(session);

    lifecycle.startFirstListedVariant(session);

    var request = transcodeExecutor.getStartedRequests().getLast();
    assertThat(request.width()).isEqualTo(1080);
    assertThat(request.height()).isEqualTo(1920);
  }

  @Test
  @DisplayName("Should retain the variant geometry when recovering one ABR producer")
  void shouldRetainVariantGeometryWhenRecoveringOneAbrProducer() {
    var session = startedLadderSession();
    transcodeExecutor.markDead(session.getSessionId(), "480p");

    var result =
        lifecycle.recover(
            earliestArrivingSegmentRequestBuilder()
                .sessionId(session.getSessionId())
                .variantLabel("480p")
                .segmentName("480p/segment2.m4s")
                .build());

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    var request = transcodeExecutor.getStartedRequests().getLast();
    assertThat(request.variantLabel()).isEqualTo("480p");
    assertThat(request.width()).isEqualTo(854);
    assertThat(request.height()).isEqualTo(480);
    assertThat(request.bitrate()).isEqualTo(1_500_000L);
    assertThat(request.attemptId())
        .isEqualTo(session.getVariantHandle("480p").orElseThrow().attemptId());
  }

  @Test
  @DisplayName(
      "Should leave the job attempt at the newer request's segment when an older request recovers after the newer request relocated the variant")
  void
      shouldLeaveTheJobAttemptAtTheNewerRequestsSegmentWhenAnOlderRequestRecoversAfterTheNewerRequestRelocatedTheVariant() {
    var session = startedSession();
    var sessionId = session.getSessionId();
    var older =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment1.m4s");
    var newer =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment100.m4s");
    lifecycle.recover(newer);

    var result = lifecycle.recover(older);

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.SUPERSEDED);
    assertThat(transcodeExecutor.getStartedRequests())
        .extracting(TranscodeRequest::startSequenceNumber)
        .containsExactly(0, 100);
    assertThat(session.getHandle().orElseThrow().startSequenceNumber()).isEqualTo(100);
  }

  @Test
  @DisplayName(
      "Should replace the job attempt at the newer request's segment when an older request recovers first after the job attempt that the newer request started dies")
  void
      shouldReplaceTheJobAttemptAtTheNewerRequestsSegmentWhenAnOlderRequestRecoversFirstAfterTheJobAttemptThatTheNewerRequestStartedDies() {
    var session = startedSession();
    var sessionId = session.getSessionId();
    var older =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment101.m4s");
    lifecycle.recover(older);
    var newer =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment100.m4s");
    lifecycle.recover(newer);
    transcodeExecutor.markDead(sessionId);

    var olderResult = lifecycle.recover(older);
    var newerResult = lifecycle.recover(newer);

    assertThat(olderResult).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(newerResult).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(transcodeExecutor.getStartedRequests())
        .extracting(TranscodeRequest::startSequenceNumber)
        .containsExactly(0, 101, 100, 100);
  }

  @Test
  @DisplayName(
      "Should replace the job attempt at the first segment it did not store when a later superseded request recovers first after the job attempt dies")
  void
      shouldReplaceTheJobAttemptAtTheFirstSegmentItDidNotStoreWhenALaterSupersededRequestRecoversFirstAfterTheJobAttemptDies() {
    var session = startedSession();
    var sessionId = session.getSessionId();
    var first =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment101.m4s");
    lifecycle.recover(first);
    var second =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment102.m4s");
    lifecycle.recover(second);
    var seek =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment100.m4s");
    lifecycle.recover(seek);
    segmentStore.addSegment(sessionId, "segment100.m4s", new byte[] {1});
    transcodeExecutor.markDead(sessionId);

    var secondResult = lifecycle.recover(second);
    var firstResult = lifecycle.recover(first);

    assertThat(secondResult).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(firstResult).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(transcodeExecutor.getStartedRequests())
        .extracting(TranscodeRequest::startSequenceNumber)
        .containsExactly(0, 101, 100, 101);
  }

  @Test
  @DisplayName(
      "Should replace the job attempt at the newer request's segment when the newer request's relocation stops the job attempt and its new job attempt fails to start")
  void
      shouldReplaceTheJobAttemptAtTheNewerRequestsSegmentWhenTheNewerRequestsRelocationStopsTheJobAttemptAndItsNewJobAttemptFailsToStart() {
    var session = startedSession();
    var sessionId = session.getSessionId();
    var older =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment100.m4s");
    lifecycle.recover(older);
    transcodeExecutor.failUntargetedStarts();
    var newer =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment300.m4s");

    var result = lifecycle.recover(newer);

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(transcodeExecutor.getStartedRequests())
        .extracting(TranscodeRequest::startSequenceNumber)
        .containsExactly(0, 100, 300);
  }

  @Test
  @DisplayName(
      "Should replace the job attempt at the newer request's segment when the newer request's resume fails to start a job attempt")
  void
      shouldReplaceTheJobAttemptAtTheNewerRequestsSegmentWhenTheNewerRequestsResumeFailsToStartAJobAttempt() {
    var session = startedSession();
    var sessionId = session.getSessionId();
    var older =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment100.m4s");
    lifecycle.recover(older);
    lifecycle.suspend(sessionId);
    transcodeExecutor.failUntargetedStarts();
    var newer =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment300.m4s");

    var result = lifecycle.recover(newer);

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(transcodeExecutor.getStartedRequests())
        .extracting(TranscodeRequest::startSequenceNumber)
        .containsExactly(0, 100, 300);
  }

  @Test
  @DisplayName(
      "Should relocate the variant to an older held request's segment when the newer of two held requests replaced their stalled job attempt at its own segment")
  void
      shouldRelocateTheVariantToAnOlderHeldRequestsSegmentWhenTheNewerOfTwoHeldRequestsReplacedTheirStalledJobAttemptAtItsOwnSegment() {
    var session = startedSession();
    var sessionId = session.getSessionId();
    for (var produced = 0; produced < 5; produced++) {
      segmentStore.addSegment(sessionId, "segment" + produced + ".m4s", new byte[] {1});
    }
    var older =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment5.m4s");
    var newer =
        lifecycle.prepareRequestedVariant(
            sessionId, StreamSession.defaultVariant(), "segment6.m4s");
    lifecycle.recover(older);
    lifecycle.recover(newer);
    clock.advance(Duration.ofSeconds(11));

    var newerResult = lifecycle.recover(newer);
    var olderResult = lifecycle.recover(older);

    assertThat(newerResult).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(olderResult).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(transcodeExecutor.getStartedRequests())
        .extracting(TranscodeRequest::startSequenceNumber)
        .containsExactly(0, 6, 5);
  }

  @Test
  @DisplayName(
      "Should report the variant not running when recovery finds a variant without a job attempt")
  void shouldReportTheVariantNotRunningWhenRecoveryFindsAVariantWithoutAJobAttempt() {
    var session = startedLadderSession();
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var result =
        lifecycle.recover(
            earliestArrivingSegmentRequestBuilder()
                .sessionId(session.getSessionId())
                .variantLabel("720p")
                .segmentName("720p/segment2.m4s")
                .build());

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.VARIANT_NOT_RUNNING);
    assertThat(startedSince(startedBefore)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should replace a dead variant at the current segment when its initialization segment is requested")
  void shouldReplaceADeadVariantAtTheCurrentSegmentWhenItsInitializationSegmentIsRequested() {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    var request = lifecycle.prepareRequestedVariant(sessionId, "1080p", "1080p/init.mp4");
    lifecycle.ensurePositioned(sessionId, "480p", "480p/segment50.m4s");
    transcodeExecutor.markDead(sessionId, "1080p");
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var result = lifecycle.recover(request);

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(startedSince(startedBefore))
        .singleElement()
        .satisfies(
            replacement -> {
              assertThat(replacement.variantLabel()).isEqualTo("1080p");
              assertThat(replacement.startSequenceNumber()).isEqualTo(50);
            });
  }

  @ParameterizedTest(name = "stored: {0}")
  @CsvSource({"false, 30", "true, 31"})
  @DisplayName(
      "Should start a variant where the player needs its next segment when its initialization segment is requested")
  void shouldStartAVariantWhereThePlayerNeedsItsNextSegmentWhenItsInitializationSegmentIsRequested(
      boolean newestRequestedSegmentStored, int expectedStart) {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    lifecycle.prepareRequestedVariant(sessionId, "480p", "480p/segment30.m4s");
    if (newestRequestedSegmentStored) {
      segmentStore.addSegment(sessionId, "480p/segment30.m4s", new byte[] {1});
    }

    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.prepareRequestedVariant(sessionId, "1080p", "1080p/init.mp4");

    assertThat(startedSince(startedBefore))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.variantLabel()).isEqualTo("1080p");
              assertThat(request.startSequenceNumber()).isEqualTo(expectedStart);
            });
  }

  @Test
  @DisplayName(
      "Should start a variant at the last advertised segment when its initialization segment is requested after the player received that segment")
  void
      shouldStartAVariantAtTheLastAdvertisedSegmentWhenItsInitializationSegmentIsRequestedAfterThePlayerReceivedThatSegment() {
    var session =
        defaultLadderSessionBuilder()
            .mediaProbe(defaultProbeBuilder().duration(Duration.ofSeconds(60)).build())
            .build();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    var sessionId = session.getSessionId();
    segmentStore.addSegment(sessionId, "480p/segment9.m4s", new byte[] {1});
    lifecycle.prepareRequestedVariant(sessionId, "480p", "480p/segment9.m4s");
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.prepareRequestedVariant(sessionId, "1080p", "1080p/init.mp4");

    assertThat(startedSince(startedBefore))
        .singleElement()
        .satisfies(request -> assertThat(request.startSequenceNumber()).isEqualTo(9));
  }

  @Test
  @DisplayName("Should report session gone when recovery follows destruction")
  void shouldReportSessionGoneWhenRecoveryFollowsDestruction() {
    var session = startedSession();
    lifecycle.removeSession(session.getSessionId());

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.SESSION_GONE);
  }

  @Test
  @DisplayName("Should leave the producer alone when the requested segment already exists")
  void shouldLeaveProducerAloneWhenRequestedSegmentAlreadyExists() {
    var session = startedSession();
    var attempt = session.getHandle().orElseThrow().attemptId();
    segmentStore.addSegment(session.getSessionId(), "segment2.m4s", new byte[] {1});

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attempt);
    assertThat(transcodeExecutor.getStoppedVariants()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeStatus.class,
      names = {"STOPPED", "STARTING", "SEEKING"})
  @DisplayName("Should leave a planned transition alone when recovery observes its handle")
  void shouldLeavePlannedTransitionAloneWhenRecoveryObservesItsHandle(TranscodeStatus status) {
    var session = startedSession();
    var handle = session.getHandle().orElseThrow().withStatus(status);
    session.setHandle(handle);
    transcodeExecutor.markDead(session.getSessionId());

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(session.getHandle()).contains(handle);
    assertThat(transcodeExecutor.getStartedTargets()).isEmpty();
  }

  @Test
  @DisplayName("Should recover a suspended variant when its siblings keep the session live")
  void shouldRecoverSuspendedVariantWhenItsSiblingsKeepSessionLive() {
    var session = startedLadderSession();
    var starting =
        lifecycle.prepareRequestedVariant(session.getSessionId(), "1080p", "1080p/init.mp4");
    var sibling = session.getVariantHandle("480p").orElseThrow();
    var suspended =
        session.getVariantHandle("1080p").orElseThrow().withStatus(TranscodeStatus.SUSPENDED);
    session.setVariantHandle("1080p", suspended);
    transcodeExecutor.markDead(session.getSessionId(), "1080p");

    var result =
        lifecycle.recover(
            SegmentRequest.builder()
                .sessionId(session.getSessionId())
                .variantLabel("1080p")
                .segmentName("1080p/segment2.m4s")
                .arrival(starting.arrival() + 1)
                .build());

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(session.getVariantHandle("1080p").orElseThrow().status())
        .isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(session.getVariantHandle("480p")).contains(sibling);
  }

  @Test
  @DisplayName("Should revive an exhausted variant when a new target becomes eligible")
  void shouldReviveExhaustedVariantWhenNewTargetBecomesEligible() {
    var session = startedSession();
    transcodeExecutor.markDead(session.getSessionId());
    transcodeExecutor.refuseTarget(ExecutionTargetId.LOCAL);
    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);
    var newTarget = new ExecutionTargetId("new-worker");
    transcodeExecutor.setExecutionTargets(List.of(newTarget));

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(newTarget);
  }

  @Test
  @DisplayName(
      "Should keep an exhausted encode exhausted when the only new target cannot encode its codec family")
  void shouldKeepAnExhaustedEncodeExhaustedWhenTheOnlyNewTargetCannotEncodeItsCodecFamily() {
    var session = defaultSessionBuilder().transcodeDecision(fullTranscodeDecision("av1")).build();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    recover(session);
    clock.advance(Duration.ofSeconds(16));
    recover(session);
    // The replacement encodes from segment 2, so its preroll segment adds 6 s to its startup
    // budget.
    clock.advance(Duration.ofSeconds(22));
    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);
    var h264OnlyTarget = new ExecutionTargetId("h264-only-worker");
    transcodeExecutor.setTargetEncodableVideoCodecFamilies(h264OnlyTarget, Set.of("h264"));
    transcodeExecutor.setExecutionTargets(List.of(ExecutionTargetId.LOCAL, h264OnlyTarget));

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(ExecutionTargetId.LOCAL);
  }

  @Test
  @DisplayName("Should stop the final stalled producer when every target has been tried")
  void shouldStopFinalStalledProducerWhenEveryTargetHasBeenTried() {
    var session = startedSession();
    recover(session);
    clock.advance(Duration.ofSeconds(16));
    recover(session);
    clock.advance(Duration.ofSeconds(16));

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isFalse();
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(ExecutionTargetId.LOCAL);
  }

  @Test
  @DisplayName("Should stop every producer under the session mutex when destroying")
  void shouldStopEveryProducerUnderTheSessionMutexWhenDestroying() {
    var session = startedSession();

    lifecycle.stopForDestroy(session.getSessionId());

    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isFalse();
  }

  private StreamSession startedTranscodeSession(String videoCodecFamily) {
    var session =
        abrSessionBuilder().transcodeDecision(fullTranscodeDecision(videoCodecFamily)).build();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    return session;
  }

  @Test
  @DisplayName("Should carry the faster preset when an H.264 variant job starts")
  void shouldCarryFasterPresetWhenH264VariantJobStarts() {
    startedTranscodeSession("h264");

    assertThat(transcodeExecutor.getStartedRequests())
        .extracting(TranscodeRequest::encoderPreset)
        .containsOnly(EncoderPreset.FASTER);
  }

  @Test
  @DisplayName("Should carry the faster preset when an AV1 variant job starts")
  void shouldCarryFasterPresetWhenAv1VariantJobStarts() {
    startedTranscodeSession("av1");

    assertThat(transcodeExecutor.getStartedRequests())
        .extracting(TranscodeRequest::encoderPreset)
        .containsOnly(EncoderPreset.FASTER);
  }

  @Test
  @DisplayName("Should carry the veryfast preset when an HEVC variant job starts")
  void shouldCarryVeryfastPresetWhenHevcVariantJobStarts() {
    startedTranscodeSession("hevc");

    assertThat(transcodeExecutor.getStartedRequests())
        .extracting(TranscodeRequest::encoderPreset)
        .containsOnly(EncoderPreset.VERYFAST);
  }

  @Test
  @DisplayName("Should carry the first attempt's preset when a replacement attempt starts")
  void shouldCarryTheFirstAttemptsPresetWhenReplacementAttemptStarts() {
    var session = startedTranscodeSession("hevc");
    var firstAttemptPreset = transcodeExecutor.getStartedRequests().getFirst().encoderPreset();
    transcodeExecutor.markDead(session.getSessionId(), "1080p");
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.recover(
        earliestArrivingSegmentRequestBuilder()
            .sessionId(session.getSessionId())
            .variantLabel("1080p")
            .segmentName("1080p/segment2.m4s")
            .build());
    lifecycle.ensurePositioned(session.getSessionId(), "1080p", "1080p/segment100.m4s");

    assertThat(startedSince(startedBefore))
        .hasSize(2)
        .extracting(TranscodeRequest::encoderPreset)
        .containsOnly(firstAttemptPreset);
  }

  @Test
  @DisplayName("Should carry the first attempt's preset when a suspended session resumes")
  void shouldCarryTheFirstAttemptsPresetWhenSuspendedSessionResumes() {
    var session = startedTranscodeSession("hevc");
    var firstAttemptPreset = transcodeExecutor.getStartedRequests().getFirst().encoderPreset();
    serverSettings.changeEncoderPreset(serverAdmin, "SLOWER");
    lifecycle.suspend(session.getSessionId());
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "1080p", "1080p/segment5.m4s");

    assertThat(startedSince(startedBefore))
        .singleElement()
        .extracting(TranscodeRequest::encoderPreset)
        .isEqualTo(firstAttemptPreset);
  }

  @Test
  @DisplayName(
      "Should pin only the first-listed variant to the encoder backend of its first job attempt when a stream session starts")
  void
      shouldPinOnlyTheFirstListedVariantToTheEncoderBackendOfItsFirstJobAttemptWhenAStreamSessionStarts() {
    var session = sessionPinnedToLibx264(abrSessionBuilder());

    assertThat(session.getPinnedEncoder("1080p")).contains(libx264());
    assertThat(session.getPinnedEncoder("1080p").orElseThrow().keyframeVerified()).isTrue();
    assertThat(session.getPinnedEncoder("720p")).isEmpty();
  }

  @Test
  @DisplayName("Should pin no encoder backend when the variant copies the video")
  void shouldPinNoEncoderBackendWhenVariantCopiesTheVideo() {
    transcodeExecutor.setTargetEncoders(LIBX264_WORKER, List.of(libx264()));
    transcodeExecutor.setExecutionTargets(List.of(LIBX264_WORKER));

    var session = startedSession();

    assertThat(session.getPinnedEncoder(StreamSession.defaultVariant())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should run a replacement attempt only on a worker with the pinned encoder backend when recovering a dead producer")
  void shouldRunReplacementAttemptOnlyOnWorkerWithPinnedEncoderBackendWhenRecoveringDeadProducer() {
    var session = sessionPinnedToLibx264(defaultSessionBuilder());
    transcodeExecutor.setExecutionTargets(List.of(NVENC_WORKER, LIBX264_WORKER));
    transcodeExecutor.markDead(session.getSessionId());

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(LIBX264_WORKER);
    assertThat(session.getHandle().orElseThrow().encoder()).contains(libx264());
  }

  @Test
  @DisplayName(
      "Should run a replacement attempt only on a keyframe-verified worker when the pinned encoder is keyframe-verified")
  void
      shouldRunReplacementAttemptOnlyOnKeyframeVerifiedWorkerWhenPinnedEncoderIsKeyframeVerified() {
    var session = sessionPinnedToLibx264(defaultSessionBuilder());
    transcodeExecutor.setTargetEncoders(
        UNVERIFIED_LIBX264_WORKER, List.of(libx264NotKeyframeVerified()));
    transcodeExecutor.setExecutionTargets(List.of(UNVERIFIED_LIBX264_WORKER, LIBX264_WORKER));
    transcodeExecutor.markDead(session.getSessionId());

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(LIBX264_WORKER);
    assertThat(session.getHandle().orElseThrow().encoder()).contains(libx264());
    assertThat(session.getPinnedEncoder(StreamSession.defaultVariant())).contains(libx264());
  }

  @Test
  @DisplayName(
      "Should exhaust recovery when only a worker without keyframe verification has the pinned encoder backend")
  void shouldExhaustRecoveryWhenOnlyWorkerWithoutKeyframeVerificationHasPinnedEncoderBackend() {
    var session = sessionPinnedToLibx264(defaultSessionBuilder());
    transcodeExecutor.setTargetEncoders(
        UNVERIFIED_LIBX264_WORKER, List.of(libx264NotKeyframeVerified()));
    transcodeExecutor.setExecutionTargets(List.of(UNVERIFIED_LIBX264_WORKER));
    transcodeExecutor.markDead(session.getSessionId());

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);

    assertThat(transcodeExecutor.getStartedTargets()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should exhaust recovery without starting an attempt when no worker has the pinned encoder backend")
  void shouldExhaustRecoveryWithoutStartingAttemptWhenNoWorkerHasPinnedEncoderBackend() {
    var session = sessionPinnedToLibx264(defaultSessionBuilder());
    transcodeExecutor.setExecutionTargets(List.of(NVENC_WORKER));
    transcodeExecutor.markDead(session.getSessionId());

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);

    assertThat(transcodeExecutor.getStartedTargets()).isEmpty();
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName(
      "Should keep an exhausted variant exhausted when only a worker without the pinned encoder backend joins")
  void shouldKeepExhaustedVariantExhaustedWhenOnlyWorkerWithoutPinnedEncoderBackendJoins() {
    var session = sessionPinnedToLibx264(defaultSessionBuilder());
    transcodeExecutor.setExecutionTargets(List.of());
    transcodeExecutor.markDead(session.getSessionId());
    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);
    transcodeExecutor.setExecutionTargets(List.of(NVENC_WORKER));

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);

    assertThat(transcodeExecutor.getStartedTargets()).isEmpty();
  }

  @Test
  @DisplayName("Should relocate a variant only onto its pinned encoder backend")
  void shouldRelocateVariantOnlyOntoItsPinnedEncoderBackend() {
    var session = sessionPinnedToLibx264(defaultSessionBuilder());
    transcodeExecutor.setExecutionTargets(List.of(NVENC_WORKER, LIBX264_WORKER));
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment100.m4s");

    assertThat(startedSince(startedBefore))
        .singleElement()
        .extracting(TranscodeRequest::pinnedEncoder)
        .isEqualTo(Optional.of(libx264()));
    assertThat(session.getHandle().orElseThrow().encoder()).contains(libx264());
  }

  @Test
  @DisplayName("Should start no relocation when no worker has the pinned encoder backend")
  void shouldStartNoRelocationWhenNoWorkerHasPinnedEncoderBackend() {
    var session = sessionPinnedToLibx264(defaultSessionBuilder());
    transcodeExecutor.setExecutionTargets(List.of(NVENC_WORKER));
    var sessionId = session.getSessionId();
    var variantLabel = StreamSession.defaultVariant();
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    assertThatThrownBy(() -> lifecycle.ensurePositioned(sessionId, variantLabel, "segment100.m4s"))
        .isInstanceOf(TranscodeException.class);

    assertThat(startedSince(startedBefore)).isEmpty();
  }

  @Test
  @DisplayName("Should resume a suspended variant only on its pinned encoder backend")
  void shouldResumeSuspendedVariantOnlyOnItsPinnedEncoderBackend() {
    var session = sessionPinnedToLibx264(defaultSessionBuilder());
    lifecycle.suspend(session.getSessionId());
    transcodeExecutor.setExecutionTargets(List.of(NVENC_WORKER, LIBX264_WORKER));

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment5.m4s");

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(session.getHandle().orElseThrow().encoder()).contains(libx264());
  }

  @Test
  @DisplayName(
      "Should exhaust recovery of a suspended variant when no worker has its pinned encoder backend")
  void shouldExhaustRecoveryOfSuspendedVariantWhenNoWorkerHasItsPinnedEncoderBackend() {
    var session = sessionPinnedToLibx264(defaultSessionBuilder());
    lifecycle.suspend(session.getSessionId());
    transcodeExecutor.setExecutionTargets(List.of(NVENC_WORKER));

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);

    assertThat(transcodeExecutor.getStartedTargets()).isEmpty();
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  private StreamSession sessionPinnedToLibx264(StreamSession.StreamSessionBuilder session) {
    var started = session.transcodeDecision(fullTranscodeDecision("h264")).build();
    transcodeExecutor.setTargetEncoders(LIBX264_WORKER, List.of(libx264()));
    transcodeExecutor.setTargetEncoders(NVENC_WORKER, List.of(h264Nvenc()));
    transcodeExecutor.setExecutionTargets(List.of(LIBX264_WORKER));
    runtimeRegistry.save(started);
    lifecycle.startFirstListedVariant(started);
    return started;
  }

  @Test
  @DisplayName(
      "Should carry medium when a ServerAdmin sets medium before the next variant job starts")
  void shouldCarryMediumWhenServerAdminSetsMediumBeforeNextVariantJob() {
    serverSettings.changeEncoderPreset(serverAdmin, "MEDIUM");

    startedTranscodeSession("h264");

    assertThat(transcodeExecutor.getStartedRequests())
        .isNotEmpty()
        .extracting(TranscodeRequest::encoderPreset)
        .containsOnly(EncoderPreset.MEDIUM);
  }

  @Test
  @DisplayName("Should keep the first preset when a replacement attempt starts after a change")
  void shouldKeepFirstPresetWhenReplacementAttemptStartsAfterChange() {
    var session = startedTranscodeSession("h264");
    serverSettings.changeEncoderPreset(serverAdmin, "SLOWER");
    transcodeExecutor.markDead(session.getSessionId(), "1080p");
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.recover(
        earliestArrivingSegmentRequestBuilder()
            .sessionId(session.getSessionId())
            .variantLabel("1080p")
            .segmentName("1080p/segment2.m4s")
            .build());
    lifecycle.ensurePositioned(session.getSessionId(), "1080p", "1080p/segment100.m4s");

    assertThat(startedSince(startedBefore))
        .hasSize(2)
        .extracting(TranscodeRequest::encoderPreset)
        .containsOnly(EncoderPreset.FASTER);
  }

  @Test
  @DisplayName("Should carry the new preset when a variant starts after a change")
  void shouldCarryNewPresetWhenVariantStartsAfterChange() {
    startedTranscodeSession("h264");
    serverSettings.changeEncoderPreset(serverAdmin, "SLOWER");
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    startedTranscodeSession("h264");

    assertThat(startedSince(startedBefore))
        .isNotEmpty()
        .extracting(TranscodeRequest::encoderPreset)
        .containsOnly(EncoderPreset.SLOWER);
  }

  @Test
  @DisplayName(
      "Should pin a later variant at its own first job attempt and reuse each pin when a stopped variant starts again")
  void
      shouldPinALaterVariantAtItsOwnFirstJobAttemptAndReuseEachPinWhenAStoppedVariantStartsAgain() {
    transcodeExecutor.setTargetEncoders(LIBX264_WORKER, List.of(libx264()));
    transcodeExecutor.setTargetEncoders(NVENC_WORKER, List.of(h264Nvenc()));
    transcodeExecutor.setExecutionTargets(List.of(LIBX264_WORKER));
    var session = defaultLadderSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    serverSettings.changeEncoderPreset(serverAdmin, "SLOWER");
    transcodeExecutor.setExecutionTargets(List.of(NVENC_WORKER, LIBX264_WORKER));

    lifecycle.prepareRequestedVariant(sessionId, "720p", "720p/segment3.m4s");
    lifecycle.prepareRequestedVariant(sessionId, "480p", "480p/segment9.m4s");

    assertThat(transcodeExecutor.getStartedRequests())
        .extracting(
            TranscodeRequest::variantLabel,
            TranscodeRequest::startSequenceNumber,
            TranscodeRequest::encoderPreset,
            TranscodeRequest::pinnedEncoder)
        .containsExactly(
            tuple("480p", 0, EncoderPreset.FASTER, Optional.empty()),
            tuple("720p", 3, EncoderPreset.SLOWER, Optional.empty()),
            tuple("480p", 9, EncoderPreset.FASTER, Optional.of(libx264())));
    assertThat(session.getPinnedEncoder("720p")).contains(h264Nvenc());
    assertThat(session.getVariantHandles()).containsOnlyKeys("480p");
  }

  private List<TranscodeRequest> startedSince(int startedBefore) {
    var started = transcodeExecutor.getStartedRequests();
    return started.subList(startedBefore, started.size());
  }

  // Keyframes every 5.005 s present 42 ms after they decode, so a segment's first keyframe lies
  // inside its interval but never at its grid start. Segment 2, [12 s, 18 s), opens at the keyframe
  // that decodes at 15.015 s; segment 5 at 30.03 s; segment 100 at 600.6 s.
  private StreamSession startedIrregularCopySession() {
    var keyframes =
        LongStream.iterate(0, time -> time <= 7_200_000, time -> time + 5_005)
            .mapToObj(time -> new KeyframePosition(time + 42, time))
            .toList();
    return startedCopySession(defaultProbeBuilder().keyframes(knownKeyframes(keyframes)));
  }

  // A 20 s source with keyframes at 0, 6 and 12 s: the grid's last interval, [18 s, 20 s), holds
  // no keyframe and joins segment 2, so the copy advertises 6, 6 and 8 s.
  private StreamSession startedCopySessionWhoseLastSegmentAbsorbsTheFinalGridInterval() {
    var keyframes =
        LongStream.of(0, 6_000, 12_000).mapToObj(time -> new KeyframePosition(time, time)).toList();
    return startedCopySession(
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(20))
            .keyframes(knownKeyframes(keyframes)));
  }

  private StreamSession startedCopySession(MediaProbe.MediaProbeBuilder probe) {
    var session = defaultSessionBuilder().mediaProbe(probe.build()).build();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    return session;
  }

  private static KeyframeKnowledge.Known knownKeyframes(List<KeyframePosition> keyframes) {
    return new KeyframeKnowledge.Known(
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(1_000)
            .source(KeyframePositionSource.CONTAINER_INDEX)
            .keyframes(keyframes)
            .build());
  }

  @Test
  @DisplayName("Should start a copy attempt at segment 0 without a keyframe seek")
  void shouldStartACopyAttemptAtSegment0WithoutAKeyframeSeek() {
    startedIrregularCopySession();

    assertThat(transcodeExecutor.getStartedRequests())
        .singleElement()
        .extracting(TranscodeRequest::keyframeSeekPosition)
        .isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("Should start a relocated copy attempt at its segment's first keyframe")
  void shouldStartARelocatedCopyAttemptAtItsSegmentsFirstKeyframe() {
    var session = startedIrregularCopySession();

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment100.m4s");

    var request = transcodeExecutor.getStartedRequests().getLast();
    assertThat(request.startSequenceNumber()).isEqualTo(100);
    assertThat(request.keyframeSeekPosition()).hasValue(Duration.ofMillis(600_600));
  }

  @Test
  @DisplayName("Should start a replacement copy attempt at its segment's first keyframe")
  void shouldStartAReplacementCopyAttemptAtItsSegmentsFirstKeyframe() {
    var session = startedIrregularCopySession();
    transcodeExecutor.markDead(session.getSessionId());

    recover(session);

    var request = transcodeExecutor.getStartedRequests().getLast();
    assertThat(request.startSequenceNumber()).isEqualTo(2);
    assertThat(request.keyframeSeekPosition()).hasValue(Duration.ofMillis(15_015));
  }

  @Test
  @DisplayName("Should resume a suspended copy session at its requested segment's first keyframe")
  void shouldResumeASuspendedCopySessionAtItsRequestedSegmentsFirstKeyframe() {
    var session = startedIrregularCopySession();
    suspendHandle(session);

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment5.m4s");

    var request = transcodeExecutor.getStartedRequests().getLast();
    assertThat(request.startSequenceNumber()).isEqualTo(5);
    assertThat(request.keyframeSeekPosition()).hasValue(Duration.ofMillis(30_030));
  }

  @Test
  @DisplayName(
      "Should start a replacement copy attempt at its first keyframe when its segment absorbs the final grid interval")
  void
      shouldStartAReplacementCopyAttemptAtItsFirstKeyframeWhenItsSegmentAbsorbsTheFinalGridInterval() {
    var session = startedCopySessionWhoseLastSegmentAbsorbsTheFinalGridInterval();
    transcodeExecutor.markDead(session.getSessionId());

    recover(session);

    var request = transcodeExecutor.getStartedRequests().getLast();
    assertThat(request.startSequenceNumber()).isEqualTo(2);
    assertThat(request.keyframeSeekPosition()).hasValue(Duration.ofSeconds(12));
    assertThat(request.mediaSegmentCount()).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "Should resume a suspended copy session at its first keyframe when its segment absorbs the final grid interval")
  void
      shouldResumeASuspendedCopySessionAtItsFirstKeyframeWhenItsSegmentAbsorbsTheFinalGridInterval() {
    var session = startedCopySessionWhoseLastSegmentAbsorbsTheFinalGridInterval();
    suspendHandle(session);

    lifecycle.ensurePositioned(
        session.getSessionId(), StreamSession.defaultVariant(), "segment2.m4s");

    var request = transcodeExecutor.getStartedRequests().getLast();
    assertThat(request.startSequenceNumber()).isEqualTo(2);
    assertThat(request.keyframeSeekPosition()).hasValue(Duration.ofSeconds(12));
    assertThat(request.mediaSegmentCount()).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "Should refuse a request for the absorbed final grid interval as an unadvertised copy segment")
  void shouldRefuseARequestForTheAbsorbedFinalGridIntervalAsAnUnadvertisedCopySegment() {
    var session = startedCopySessionWhoseLastSegmentAbsorbsTheFinalGridInterval();
    transcodeExecutor.markDead(session.getSessionId());
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var result =
        lifecycle.recover(
            earliestArrivingSegmentRequestBuilder()
                .sessionId(session.getSessionId())
                .segmentName("segment3.m4s")
                .build());

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.SEGMENT_NOT_ADVERTISED);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startedBefore);
  }

  @Test
  @DisplayName("Should send no keyframe seek position when a relocated attempt encodes the video")
  void shouldSendNoKeyframeSeekPositionWhenARelocatedAttemptEncodesTheVideo() {
    var session = startedTranscodeSession("h264");
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "1080p", "1080p/segment100.m4s");

    assertThat(startedSince(startedBefore))
        .isNotEmpty()
        .extracting(TranscodeRequest::keyframeSeekPosition)
        .containsOnly(Optional.empty());
  }
}
