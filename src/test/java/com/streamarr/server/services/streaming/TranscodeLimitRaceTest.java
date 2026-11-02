package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.mutation.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Transcode Limit Race Tests")
class TranscodeLimitRaceTest {

  private static final int TRANSCODE_LIMIT = 3;
  private static final long TIMEOUT_SECONDS = 5;

  private final FakeMediaFileRepository mediaFileRepository = new FakeMediaFileRepository();
  private final FakeSegmentStore segmentStore = new FakeSegmentStore();
  private final DispatchHoldingExecutor transcodeExecutor = new DispatchHoldingExecutor();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private int transcodeLimit;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private ProducerLifecycleService lifecycle;
  private SegmentDeliveryCoordinator coordinator;
  private HlsStreamingService service;

  // How a stream session starts a job attempt: by its creation, or by a segment request for a
  // variant without a job attempt.
  private enum Start {
    SESSION_CREATION,
    ON_DEMAND_START
  }

  // A start ready to run, with what it answers when it starts, when the limit refuses it and when
  // its dispatch fails. Producing stores the segment that an on-demand start waits for.
  @Builder
  private record PreparedStart(
      Callable<Object> run,
      Object refusal,
      Class<?> startedOutcome,
      Class<?> failedDispatchOutcome,
      Runnable produce) {}

  @BeforeEach
  void setUp() {
    useRig(TRANSCODE_LIMIT, new FakeRuntimeStreamSessionRegistry());
  }

  private void useRig(int limit, FakeRuntimeStreamSessionRegistry registry) {
    transcodeLimit = limit;
    runtimeRegistry = registry;
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(limit)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(registry)
            .heldRequests(transcodeExecutor.heldSegmentRequests())
            .sessionMutex(new MutexFactory<>())
            .build();
    lifecycle = rig.lifecycle();
    coordinator = rig.coordinator();
    var probeResults = new FakeMediaFileContainerInfoRepository();
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());
    service =
        HlsStreamingService.builder()
            .mediaFileRepository(mediaFileRepository)
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(playbackProbeServiceBuilder(probeResults).build())
            .transcodeDecisionService(new TranscodeDecisionService(properties, meters))
            .qualityLadderService(new QualityLadderService())
            .properties(properties)
            .authorityGate(new FakePlaybackAuthorityGate())
            .runtimeRegistry(registry)
            .producerLifecycle(lifecycle)
            .deliveryCoordinator(coordinator)
            .meterRegistry(meters)
            .build();
  }

  private static Stream<Arguments> pairsOfStarts() {
    return Stream.of(
        Arguments.of(Start.SESSION_CREATION, Start.SESSION_CREATION),
        Arguments.of(Start.ON_DEMAND_START, Start.ON_DEMAND_START),
        Arguments.of(Start.SESSION_CREATION, Start.ON_DEMAND_START),
        Arguments.of(Start.ON_DEMAND_START, Start.SESSION_CREATION));
  }

  @ParameterizedTest(name = "{0} then {1}")
  @MethodSource("pairsOfStarts")
  @DisplayName(
      "Should start exactly one job attempt when two stream sessions start one at the same moment and the transcode limit has one place")
  void
      shouldStartExactlyOneJobAttemptWhenTwoStreamSessionsStartOneAtTheSameMomentAndTheTranscodeLimitHasOnePlace(
          Start first, Start second) throws Exception {
    var firstStart = prepare(first);
    var secondStart = prepare(second);
    runJobAttemptsUpTo(TRANSCODE_LIMIT - 1);
    transcodeExecutor.holdDispatches(2);

    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var started = runAsync(firstStart, threads);
      awaitCompletion(transcodeExecutor.heldDispatch(1));
      var refused = runAsync(secondStart, threads);
      // The second start either is refused or is held at its own dispatch beside the first.
      awaitCompletion(CompletableFuture.anyOf(refused, transcodeExecutor.heldDispatch(2)));
      transcodeExecutor.releaseDispatches();
      firstStart.produce().run();
      secondStart.produce().run();

      assertThat(refused.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(secondStart.refusal());
      assertThat(started.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(firstStart.startedOutcome());
    }

    assertThat(transcodeExecutor.getRunningCount()).isEqualTo(TRANSCODE_LIMIT);
    assertThat(lifecycle.freeConfiguredTranscodeSlots()).isZero();
  }

  @ParameterizedTest(name = "{0} then {1}")
  @MethodSource("pairsOfStarts")
  @DisplayName(
      "Should start a stream session's job attempt while another stream session's job attempt dispatches")
  void shouldStartAStreamSessionsJobAttemptWhileAnotherStreamSessionsJobAttemptDispatches(
      Start first, Start second) throws Exception {
    useRig(TRANSCODE_LIMIT + 1, new FakeRuntimeStreamSessionRegistry());
    var firstStart = prepare(first);
    var secondStart = prepare(second);
    runJobAttemptsUpTo(TRANSCODE_LIMIT - 1);
    transcodeExecutor.holdDispatches(1);

    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var held = runAsync(firstStart, threads);
      awaitCompletion(transcodeExecutor.heldDispatch(1));
      var unheld = runAsync(secondStart, threads);
      secondStart.produce().run();

      assertThat(unheld.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(secondStart.startedOutcome());
      assertThat(held).isNotDone();
      transcodeExecutor.releaseDispatches();
      firstStart.produce().run();
      assertThat(held.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(firstStart.startedOutcome());
    }

    assertThat(lifecycle.freeConfiguredTranscodeSlots()).isZero();
  }

  @ParameterizedTest
  @EnumSource(Start.class)
  @DisplayName("Should give back a start's place under the transcode limit when its dispatch fails")
  void shouldGiveBackAStartsPlaceUnderTheTranscodeLimitWhenItsDispatchFails(Start start)
      throws Exception {
    var failing = prepare(start);
    var next = prepare(start);
    runJobAttemptsUpTo(TRANSCODE_LIMIT - 1);
    transcodeExecutor.failNextDispatch();

    assertThat(outcomeOf(failing.run())).isInstanceOf(failing.failedDispatchOutcome());

    assertThat(transcodeExecutor.getRunningCount()).isEqualTo(TRANSCODE_LIMIT - 1);
    assertThat(lifecycle.freeConfiguredTranscodeSlots()).isOne();
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var started = runAsync(next, threads);
      next.produce().run();
      assertThat(started.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(next.startedOutcome());
    }

    assertThat(lifecycle.freeConfiguredTranscodeSlots()).isZero();
  }

  @Test
  @DisplayName(
      "Should refuse a stream session for lack of transcode capacity when another start takes the last place after its admission")
  void
      shouldRefuseAStreamSessionForLackOfTranscodeCapacityWhenAnotherStartTakesTheLastPlaceAfterItsAdmission() {
    var startOnNextSave = new AtomicReference<Callable<Object>>();
    var interloperOutcome = new AtomicReference<Object>();
    useRig(
        TRANSCODE_LIMIT,
        new FakeRuntimeStreamSessionRegistry() {
          @Override
          public void save(StreamSession session) {
            super.save(session);
            var start = startOnNextSave.getAndSet(null);
            if (start != null) {
              interloperOutcome.set(outcomeOf(start));
            }
          }
        });
    runJobAttemptsUpTo(TRANSCODE_LIMIT - 1);
    var refused = prepare(Start.SESSION_CREATION);
    startOnNextSave.set(prepare(Start.SESSION_CREATION).run());

    var outcome = outcomeOf(refused.run());

    assertThat(interloperOutcome.get()).isInstanceOf(Outcome.Accepted.class);
    assertThat(outcome).isEqualTo(refused.refusal());
    assertThat(runtimeRegistry.count()).isEqualTo(TRANSCODE_LIMIT);
    assertThat(transcodeExecutor.getRunningCount()).isEqualTo(TRANSCODE_LIMIT);
    assertThat(
            meters
                .counter(
                    "streamarr.streaming.capacity_refusals",
                    "cause",
                    "no_free_configured_transcode_slot")
                .count())
        .isOne();
  }

  private static CompletableFuture<Object> runAsync(PreparedStart start, ExecutorService threads) {
    return CompletableFuture.supplyAsync(() -> outcomeOf(start.run()), threads);
  }

  private static void awaitCompletion(CompletableFuture<?> future) throws Exception {
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  // What the start answered, or what it threw.
  private static Object outcomeOf(Callable<Object> start) {
    try {
      return start.call();
    } catch (Exception failure) {
      return failure;
    }
  }

  private PreparedStart prepare(Start start) {
    return switch (start) {
      case SESSION_CREATION -> prepareSessionCreation();
      case ON_DEMAND_START -> prepareOnDemandStart();
    };
  }

  private PreparedStart prepareSessionCreation() {
    var command =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), autoQuality());
    return PreparedStart.builder()
        .run(() -> service.createSession(command))
        .refusal(
            Outcome.rejected(
                new CreateStreamSessionRejection.TranscodeCapacityUnavailable(transcodeLimit)))
        .startedOutcome(Outcome.Accepted.class)
        .failedDispatchOutcome(TranscodeException.class)
        .produce(() -> {})
        .build();
  }

  // A stream session whose first-listed variant runs, ready to request the initialization segment
  // of a variant without a job attempt.
  private PreparedStart prepareOnDemandStart() {
    var session = startTranscodingSession();
    var sessionId = session.getSessionId();
    var variantLabel =
        session.getVariants().stream()
            .map(QualityVariant::label)
            .filter(label -> session.getVariantHandle(label).isEmpty())
            .findFirst()
            .orElseThrow();
    var segmentName = variantLabel + "/init.mp4";
    return PreparedStart.builder()
        .run(() -> coordinator.deliver(sessionId, variantLabel, segmentName))
        .refusal(new SegmentDelivery.Unrecoverable())
        .startedOutcome(SegmentDelivery.Ready.class)
        .failedDispatchOutcome(SegmentDelivery.Unrecoverable.class)
        .produce(
            () -> {
              segmentStore.addSegment(sessionId, segmentName, new byte[] {1});
              transcodeExecutor.heldSegmentRequests().wakeVariant(sessionId, variantLabel);
            })
        .build();
  }

  private void runJobAttemptsUpTo(int runningJobAttempts) {
    var missing = runningJobAttempts - transcodeExecutor.getRunningCount();
    IntStream.range(0, missing).forEach(_ -> startTranscodingSession());
    assertThat(transcodeExecutor.getRunningCount()).isEqualTo(runningJobAttempts);
  }

  private StreamSession startTranscodingSession() {
    var command =
        createStreamSessionCommand(seedMediaFile().getId(), UUID.randomUUID(), autoQuality());
    var session = accepted(service.createSession(command));
    assertThat(session.getVariants()).hasSizeGreaterThan(1);
    return session;
  }

  private static StreamingOptions autoQuality() {
    return StreamingOptions.builder().quality(VideoQuality.AUTO).build();
  }

  private MediaFile seedMediaFile() {
    return mediaFileRepository.save(
        MediaFile.builder()
            .filepathUri("file:///media/movies/test.mkv")
            .filename("test.mkv")
            .status(MediaFileStatus.MATCHED)
            .size(1_000_000L)
            .build());
  }

  // Holds the next dispatches of a job attempt before the worker starts it, so the job attempt has
  // passed the transcode limit but has no handle yet.
  private static final class DispatchHoldingExecutor extends FakeTranscodeExecutor {

    private final AtomicInteger dispatchesToHold = new AtomicInteger();
    private final AtomicInteger heldDispatches = new AtomicInteger();
    private final Map<Integer, CompletableFuture<Void>> heldDispatchArrivals =
        new ConcurrentHashMap<>();
    private final AtomicBoolean failingNextDispatch = new AtomicBoolean();
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public TranscodeHandle start(TranscodeRequest request) {
      if (failingNextDispatch.compareAndSet(true, false)) {
        throw new TranscodeException("No connected transcode worker can run this variant");
      }

      if (dispatchesToHold.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
        heldDispatch(heldDispatches.incrementAndGet()).complete(null);
        awaitRelease();
      }

      return super.start(request);
    }

    private void holdDispatches(int count) {
      dispatchesToHold.set(count);
    }

    private void failNextDispatch() {
      failingNextDispatch.set(true);
    }

    // Completes once the dispatch with this ordinal, counting from 1, is held.
    private CompletableFuture<Void> heldDispatch(int ordinal) {
      return heldDispatchArrivals.computeIfAbsent(ordinal, _ -> new CompletableFuture<>());
    }

    private void releaseDispatches() {
      release.countDown();
    }

    private void awaitRelease() {
      try {
        if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          throw new IllegalStateException("The held dispatch was never released");
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
