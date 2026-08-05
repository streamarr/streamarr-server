package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.abrSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.StreamingRigFixture.StreamingRig;
import com.streamarr.server.services.concurrency.MutexFactory;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import lombok.Builder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("Segment Delivery Coordinator Tests")
class SegmentDeliveryCoordinatorTest {

  private static final Duration STALL_THRESHOLD = Duration.ofMillis(200);
  private static final ExecutionTargetId TARGET_A = new ExecutionTargetId("worker-a");
  private static final ExecutionTargetId TARGET_B = new ExecutionTargetId("worker-b");
  private static final ExecutionTargetId TARGET_C = new ExecutionTargetId("worker-c");

  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private StreamingProperties properties;
  private ProducerLifecycleService lifecycle;
  private MutableClock clock;
  private SegmentDeliveryCoordinator coordinator;

  @BeforeEach
  void setUp() {
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    clock = new MutableClock();
    properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .producerStallThreshold(STALL_THRESHOLD)
            .build();
    var rig = rigWith(transcodeExecutor, segmentStore);
    lifecycle = rig.lifecycle();
    coordinator = rig.coordinator();
  }

  private StreamingRig rigWith(FakeTranscodeExecutor executor) {
    return rigWith(executor, segmentStore);
  }

  private StreamingRig rigWith(FakeTranscodeExecutor executor, FakeSegmentStore store) {
    return rigWith(executor, store, Duration.ofMillis(20));
  }

  private StreamingRig rigWith(
      FakeTranscodeExecutor executor, FakeSegmentStore store, Duration pollInterval) {
    return StreamingRigFixture.streamingRigBuilder()
        .transcodeExecutor(executor)
        .segmentStore(store)
        .properties(properties)
        .runtimeRegistry(runtimeRegistry)
        .clock(clock)
        .pollInterval(pollInterval)
        .build();
  }

  private StreamSession startedSession() {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 0, 0);
    return session;
  }

  private StreamSession startedAbrSession() {
    var session = abrSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 0, 0);
    return session;
  }

  private CompletableFuture<SegmentDelivery> deliverAsync(UUID sessionId, String segmentName) {
    return deliverAsync(sessionId, StreamSession.defaultVariant(), segmentName);
  }

  private CompletableFuture<SegmentDelivery> deliverAsync(
      UUID sessionId, String variantLabel, String segmentName) {
    return CompletableFuture.supplyAsync(
        () -> coordinator.deliver(sessionId, variantLabel, segmentName));
  }

  private void awaitLivenessChecks(FakeTranscodeExecutor executor, int count) {
    var target = executor.livenessChecks() + count;
    executor.awaitLivenessCheckCount(target);
  }

  private void awaitLivenessChecks(int count) {
    awaitLivenessChecks(transcodeExecutor, count);
  }

  @Test
  @DisplayName(
      "Should serve a segment the moment it exists without waiting for its successor when delivering a segment")
  void shouldServeSegmentTheMomentItExistsWithoutWaitingForItsSuccessorWhenDeliveringSegment() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment0.ts", new byte[] {0x47});

    var delivery =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment0.ts");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(((SegmentDelivery.Ready) delivery).data()).containsExactly(0x47);
  }

  @Test
  @DisplayName("Should return session ended when the session does not exist")
  void shouldReturnSessionEndedWhenTheSessionDoesNotExist() {
    var delivery =
        coordinator.deliver(UUID.randomUUID(), StreamSession.defaultVariant(), "segment0.ts");

    assertThat(delivery).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName("Should return session ended when the variant is unknown on a live session")
  void shouldReturnSessionEndedWhenTheVariantIsUnknownOnALiveSession() {
    var session = startedSession();

    var delivery = coordinator.deliver(session.getSessionId(), "1080p", "1080p/segment0.ts");

    assertThat(delivery).isInstanceOf(SegmentDelivery.SessionEnded.class);
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should return session ended without entering recovery when destruction follows the initial session lookup")
  void shouldReturnSessionEndedWithoutEnteringRecoveryWhenDestructionFollowsInitialSessionLookup()
      throws Exception {
    var destroyRaceRegistry = new DestroyAfterLookupRegistry();
    runtimeRegistry = destroyRaceRegistry;
    var rig = rigWith(transcodeExecutor, segmentStore, Duration.ofDays(1));
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    rig.lifecycle().startAll(session, 0, 0);
    destroyRaceRegistry.destroyAfterNextLookup(
        () -> {
          runtimeRegistry.removeById(sessionId);
          rig.coordinator().forgetSession(sessionId);
        });

    var outcome = new AtomicReference<SegmentDelivery>();
    var delivery =
        Thread.ofVirtual()
            .name("delivery-destroy-race")
            .start(
                () ->
                    outcome.set(
                        rig.coordinator()
                            .deliver(sessionId, StreamSession.defaultVariant(), "segment0.ts")));

    try {
      delivery.join(2000);

      assertThat(delivery.isAlive()).isFalse();
      assertThat(outcome.get()).isInstanceOf(SegmentDelivery.SessionEnded.class);
    } finally {
      delivery.interrupt();
      delivery.join(2000);
    }
  }

  @Test
  @DisplayName(
      "Should serve a last-gasp publication that races the exhaustion when delivering a segment")
  void shouldServeALastGaspPublicationThatRacesTheExhaustionWhenDeliveringSegment()
      throws Exception {
    var trapStore = new TrapSegmentStore();
    var rig = rigWith(transcodeExecutor, trapStore);
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    rig.lifecycle().startAll(session, 0, 0);
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.refuseTarget(TARGET_A);
    transcodeExecutor.refuseTarget(TARGET_B);
    transcodeExecutor.markDead(sessionId);

    // Trap the exhauster between markExhausted (variant now FAILED) and its terminal check; a
    // publication landing in that window must be served, never reported unrecoverable.
    var outcome = new AtomicReference<SegmentDelivery>();
    var exhauster =
        new Thread(
            () ->
                outcome.set(
                    rig.coordinator()
                        .deliver(sessionId, StreamSession.defaultVariant(), "segment0.ts")),
            "last-gasp-exhauster");
    trapStore.armTrap(
        exhauster, () -> session.getHandle().orElseThrow().status() == TranscodeStatus.FAILED);
    exhauster.start();
    assertThat(trapStore.reachedTrap.await(5, TimeUnit.SECONDS)).isTrue();

    trapStore.addSegment(sessionId, "segment0.ts", new byte[] {0x47});
    trapStore.releaseTrap.countDown();
    exhauster.join(5000);

    assertThat(outcome.get()).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(((SegmentDelivery.Ready) outcome.get()).data()).containsExactly(0x47);
  }

  @Test
  @DisplayName(
      "Should reject a segment name matching no naming scheme without disturbing the producer when delivering a segment")
  void
      shouldRejectSegmentNameMatchingNoNamingSchemeWithoutDisturbingTheProducerWhenDeliveringSegment()
          throws Exception {
    var session = startedSession();

    var delivery = deliverAsync(session.getSessionId(), "foo.ts");

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.SessionEnded.class);
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isTrue();
    assertThat(transcodeExecutor.getStoppedVariants()).isEmpty();
  }

  @Test
  @DisplayName("Should log the swallowed read race when a destroy wins between existence and read")
  void shouldLogTheSwallowedReadRaceWhenADestroyWinsBetweenExistenceAndRead() {
    var throwingStore =
        new FakeSegmentStore() {
          @Override
          public byte[] readSegment(UUID sessionId, String segmentName) {
            throw new TranscodeException("Segment not found: " + segmentName);
          }
        };
    var sessionId = UUID.randomUUID();
    throwingStore.addSegment(sessionId, "segment0.ts", new byte[] {0x47});
    var rig = rigWith(transcodeExecutor, throwingStore);
    var logger = (Logger) LoggerFactory.getLogger(SegmentDeliveryCoordinator.class);
    logger.setLevel(Level.DEBUG);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      var delivery =
          rig.coordinator().deliver(sessionId, StreamSession.defaultVariant(), "segment0.ts");

      assertThat(delivery).isInstanceOf(SegmentDelivery.SessionEnded.class);
      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).contains("raced");
              });
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(null);
    }
  }

  @Test
  @DisplayName(
      "Should reject a segment index that overflows the naming scheme when delivering a segment")
  void shouldRejectSegmentIndexThatOverflowsTheNamingSchemeWhenDeliveringSegment() {
    var session = startedSession();

    var delivery =
        coordinator.deliver(
            session.getSessionId(),
            StreamSession.defaultVariant(),
            "segment99999999999999999999.ts");

    assertThat(delivery).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName(
      "Should keep waiting without replacement while the producer is alive when delivering a segment")
  void shouldKeepWaitingWithoutReplacementWhileTheProducerIsAliveWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment1.ts");
    // Several poll cycles pass with no publication; the frozen clock means no stall is declared.
    awaitLivenessChecks(3);
    assertThat(delivery).isNotDone();
    segmentStore.addSegment(session.getSessionId(), "segment1.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
  }

  @Test
  @DisplayName(
      "Should replace a dead producer at the requested segment's offset when delivering a segment")
  void shouldReplaceDeadProducerAtTheRequestedSegmentsOffsetWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    transcodeExecutor.markDead(session.getSessionId());
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment2.ts");
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    segmentStore.addSegment(session.getSessionId(), "segment2.ts", new byte[] {2});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    var replacement = transcodeExecutor.getStartedRequests().getLast();
    assertThat(replacement.seekPosition()).isEqualTo(12);
    assertThat(replacement.startSequenceNumber()).isEqualTo(2);
    assertThat(replacement.variantLabel()).isEqualTo(StreamSession.defaultVariant());
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(replacement.attemptId());
  }

  @Test
  @DisplayName(
      "Should replace only the dead variant while its siblings keep running when delivering a segment")
  void shouldReplaceOnlyTheDeadVariantWhileItsSiblingsKeepRunningWhenDeliveringSegment()
      throws Exception {
    var session = startedAbrSession();
    transcodeExecutor.markDead(session.getSessionId(), "1080p");
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "1080p", "1080p/segment0.ts");
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    segmentStore.addSegment(session.getSessionId(), "1080p/segment0.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    var replacement = transcodeExecutor.getStartedRequests().getLast();
    assertThat(replacement.variantLabel()).isEqualTo("1080p");
    assertThat(replacement.width()).isEqualTo(1920);
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), "720p")).isTrue();
    assertThat(session.getVariantHandle("720p").orElseThrow().status())
        .isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName(
      "Should stop then replace a producer that is alive but stalled when delivering a segment")
  void shouldStopThenReplaceProducerThatIsAliveButStalledWhenDeliveringSegment() throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment1.ts");
    // Publishing segment0 takes the run out of startup, so the stall budget that follows is the
    // steady-state threshold measured from a real publication.
    segmentStore.addSegment(session.getSessionId(), "segment0.ts", new byte[] {1});
    awaitLivenessChecks(2);
    clock.advance(STALL_THRESHOLD.plusMillis(50));
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    segmentStore.addSegment(session.getSessionId(), "segment1.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStoppedVariants())
        .contains(session.getSessionId() + "/" + StreamSession.defaultVariant());
  }

  @Test
  @DisplayName("Should replace a producer at the exact stall threshold when delivering a segment")
  void shouldReplaceAProducerAtTheExactStallThresholdWhenDeliveringSegment() throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment1.ts");
    segmentStore.addSegment(session.getSessionId(), "segment0.ts", new byte[] {1});
    awaitLivenessChecks(2);
    clock.advance(STALL_THRESHOLD);
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    segmentStore.addSegment(session.getSessionId(), "segment1.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStoppedVariants())
        .contains(session.getSessionId() + "/" + StreamSession.defaultVariant());
  }

  @Test
  @DisplayName(
      "Should not replace a cold-starting producer that is still within its startup budget when delivering a segment")
  void shouldNotReplaceColdStartingProducerWithinItsStartupBudgetWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment0.ts");
    awaitLivenessChecks(1);
    // A run that has published nothing yet has to encode a whole segment before it can; the
    // steady-state threshold alone would kill a healthy encoder and restart it identically.
    clock.advance(STALL_THRESHOLD.plusMillis(50));
    awaitLivenessChecks(3);

    assertThat(transcodeExecutor.getStoppedVariants()).isEmpty();
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
    // End the wait deterministically: cancel(true) cannot interrupt the supplier thread, and an
    // abandoned deliver would keep polling for the rest of the JVM.
    runtimeRegistry.removeById(session.getSessionId());
    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName(
      "Should observe the run's own output as progress for a request at its start index when delivering a segment")
  void shouldObserveRunsOwnOutputAsProgressForRequestAtItsStartIndexWhenDeliveringSegment()
      throws Exception {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 5400, 900);
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    // init.mp4 resolves to the run's start index, so no earlier segment can ever exist -- the
    // producer's own output is the only progress this request can observe.
    var delivery = deliverAsync(session.getSessionId(), "init.mp4");
    segmentStore.addSegment(session.getSessionId(), "segment900.m4s", new byte[] {1});
    awaitLivenessChecks(2);
    // Past the steady-state threshold but far inside the startup budget. A replacement here can
    // only mean the published sibling was counted, taking the run out of startup.
    clock.advance(STALL_THRESHOLD.plusMillis(50));

    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    awaitLivenessChecks(1);
    assertThat(transcodeExecutor.getStoppedVariants()).hasSize(1);
    // End the wait deterministically: cancel(true) cannot interrupt the supplier thread, and an
    // abandoned deliver would keep polling for the rest of the JVM.
    runtimeRegistry.removeById(session.getSessionId());
    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName("Should reset the stall clock when the producer publishes earlier segments")
  void shouldResetTheStallClockWhenTheProducerPublishesEarlierSegments() throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment2.ts");
    // Each earlier segment is published, then a poll cycle advances the frontier past it — which
    // resets the stall clock — before time advances by a sub-threshold gap. The reset keeps those
    // gaps from ever accumulating into a stall, so no replacement happens.
    segmentStore.addSegment(session.getSessionId(), "segment0.ts", new byte[] {1});
    awaitLivenessChecks(2);
    clock.advance(STALL_THRESHOLD.minusMillis(50));
    segmentStore.addSegment(session.getSessionId(), "segment1.ts", new byte[] {1});
    awaitLivenessChecks(2);
    clock.advance(STALL_THRESHOLD.minusMillis(50));
    segmentStore.addSegment(session.getSessionId(), "segment2.ts", new byte[] {2});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
  }

  @Test
  @DisplayName(
      "Should resume a session suspended mid-wait instead of classifying it as dead when delivering a segment")
  void shouldResumeSessionSuspendedMidWaitInsteadOfClassifyingItAsDeadWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment2.ts");
    // Let the delivery reach its wait loop, then suspend the session out from under it.
    awaitLivenessChecks(1);
    lifecycle.suspend(session.getSessionId());
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    segmentStore.addSegment(session.getSessionId(), "segment2.ts", new byte[] {2});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    // A planned suspension resumes through positioning; nothing is ever marked FAILED.
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(transcodeExecutor.getStartedTargets()).isEmpty();
  }

  @Test
  @DisplayName("Should try each eligible target at most once when replacements keep dying")
  void shouldTryEachEligibleTargetAtMostOnceWhenReplacementsKeepDying() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.markDead(sessionId);

    var delivery = deliverAsync(sessionId, "segment0.ts");
    transcodeExecutor.awaitStartedTargetCount(1);
    // The first replacement dies before publishing anything: recovery continues, A is not
    // retried, and target B is next.
    transcodeExecutor.markDead(sessionId);
    transcodeExecutor.awaitStartedTargetCount(2);
    segmentStore.addSegment(sessionId, "segment0.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(TARGET_A, TARGET_B);
  }

  @Test
  @DisplayName(
      "Should retry the same target after a replacement publishes progress and dies again when delivering a segment")
  void shouldRetrySameTargetAfterReplacementPublishesProgressAndDiesAgainWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A));
    transcodeExecutor.markDead(sessionId);

    var firstDelivery = deliverAsync(sessionId, "segment0.ts");
    transcodeExecutor.awaitStartedTargetCount(1);
    segmentStore.addSegment(sessionId, "segment0.ts", new byte[] {1});

    assertThat(firstDelivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);

    transcodeExecutor.markDead(sessionId);
    var secondDelivery = deliverAsync(sessionId, "segment1.ts");
    transcodeExecutor.awaitStartedTargetCount(2);
    segmentStore.addSegment(sessionId, "segment1.ts", new byte[] {2});

    assertThat(secondDelivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(TARGET_A, TARGET_A);
  }

  @Test
  @DisplayName("Should return unrecoverable and mark the variant failed when every target refuses")
  void shouldReturnUnrecoverableAndMarkTheVariantFailedWhenEveryTargetRefuses() {
    var session = startedSession();
    exhaustRecovery(session);
  }

  @Test
  @DisplayName(
      "Should not charge a stale refusal to a planned replacement attempt when delivering a segment")
  void shouldNotChargeAStaleRefusalToAPlannedReplacementAttemptWhenDeliveringSegment()
      throws Exception {
    var executor = new FakeTranscodeExecutor();
    executor.setExecutionTargets(List.of(TARGET_A));
    executor.refuseTarget(TARGET_A);
    var racingLifecycle =
        new RefusalRaceLifecycle(
            RaceLifecycleConfiguration.builder()
                .executor(executor)
                .segmentStore(segmentStore)
                .properties(properties)
                .runtimeRegistry(runtimeRegistry)
                .build());
    var racingCoordinator =
        SegmentDeliveryCoordinator.builder()
            .runtimeRegistry(runtimeRegistry)
            .segmentStore(segmentStore)
            .transcodeExecutor(executor)
            .producerLifecycle(racingLifecycle)
            .properties(properties)
            .clock(clock)
            .pollInterval(Duration.ofMillis(20))
            .build();
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    racingLifecycle.startAll(session, 0, 0);
    executor.markDead(sessionId);
    var plannedRestartObserved = new CountDownLatch(1);
    racingLifecycle.afterNextRefusal(
        () -> {
          racingLifecycle.suspend(sessionId);
          racingLifecycle.ensurePositioned(sessionId, "segment1.ts");
          var pollsBefore = executor.livenessChecks();
          var synchronizer =
              CompletableFuture.supplyAsync(
                  () ->
                      racingCoordinator.deliver(
                          sessionId, StreamSession.defaultVariant(), "segment2.ts"));
          executor.awaitLivenessCheckCount(pollsBefore + 1);
          segmentStore.addSegment(sessionId, "segment2.ts", new byte[] {2});
          try {
            assertThat(synchronizer.get(2, TimeUnit.SECONDS))
                .isInstanceOf(SegmentDelivery.Ready.class);
          } catch (Exception e) {
            throw new AssertionError("Planned replacement did not become observable", e);
          }
          executor.acceptTarget(TARGET_A);
          plannedRestartObserved.countDown();
        });

    var delivery =
        CompletableFuture.supplyAsync(
            () ->
                racingCoordinator.deliver(
                    sessionId, StreamSession.defaultVariant(), "segment1.ts"));
    assertThat(plannedRestartObserved.await(5, TimeUnit.SECONDS)).isTrue();
    executor.markDead(sessionId);
    executor.awaitStartedTargetCount(1);
    segmentStore.addSegment(sessionId, "segment1.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(executor.getStartedTargets()).containsExactly(TARGET_A);
  }

  @Test
  @DisplayName(
      "Should not record a stale replacement after a planned restart when delivering a segment")
  void shouldNotRecordAStaleReplacementAfterAPlannedRestartWhenDeliveringSegment()
      throws Exception {
    var executor = new FakeTranscodeExecutor();
    executor.setExecutionTargets(List.of(TARGET_A));
    var racingLifecycle =
        new ReplacementRaceLifecycle(
            RaceLifecycleConfiguration.builder()
                .executor(executor)
                .segmentStore(segmentStore)
                .properties(properties)
                .runtimeRegistry(runtimeRegistry)
                .build());
    var racingCoordinator =
        SegmentDeliveryCoordinator.builder()
            .runtimeRegistry(runtimeRegistry)
            .segmentStore(segmentStore)
            .transcodeExecutor(executor)
            .producerLifecycle(racingLifecycle)
            .properties(properties)
            .clock(clock)
            .pollInterval(Duration.ofMillis(20))
            .build();
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    racingLifecycle.startAll(session, 0, 0);
    executor.markDead(sessionId);
    var plannedRestartObserved = new CountDownLatch(1);
    var livenessChecksAfterRestart = new AtomicReference<Long>();
    racingLifecycle.afterNextReplacement(
        () -> {
          racingLifecycle.suspend(sessionId);
          racingLifecycle.ensurePositioned(sessionId, "segment50.ts");
          var pollsBefore = executor.livenessChecks();
          var synchronizer =
              CompletableFuture.supplyAsync(
                  () ->
                      racingCoordinator.deliver(
                          sessionId, StreamSession.defaultVariant(), "segment51.ts"));
          executor.awaitLivenessCheckCount(pollsBefore + 1);
          segmentStore.addSegment(sessionId, "segment51.ts", new byte[] {51});
          try {
            assertThat(synchronizer.get(2, TimeUnit.SECONDS))
                .isInstanceOf(SegmentDelivery.Ready.class);
          } catch (Exception e) {
            throw new AssertionError("Planned restart did not become observable", e);
          }
          livenessChecksAfterRestart.set(executor.livenessChecks());
          plannedRestartObserved.countDown();
        });
    var logger = (Logger) LoggerFactory.getLogger(SegmentDeliveryCoordinator.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    var outcome = new AtomicReference<SegmentDelivery>();
    var delivery =
        new Thread(
            () ->
                outcome.set(
                    racingCoordinator.deliver(
                        sessionId, StreamSession.defaultVariant(), "segment0.ts")));

    try {
      delivery.start();
      assertThat(plannedRestartObserved.await(5, TimeUnit.SECONDS)).isTrue();
      executor.awaitLivenessCheckCount(livenessChecksAfterRestart.get() + 1);

      assertThat(appender.list)
          .extracting(ILoggingEvent::getFormattedMessage)
          .noneMatch(message -> message.startsWith("Replaced producer"));
    } finally {
      delivery.interrupt();
      delivery.join(2000);
      logger.detachAppender(appender);
      appender.stop();
    }

    assertThat(outcome.get()).isInstanceOf(SegmentDelivery.Cancelled.class);
  }

  @Test
  @DisplayName(
      "Should answer subsequent same-window requests with unrecoverable after exhaustion when delivering a segment")
  void
      shouldAnswerSubsequentSameWindowRequestsWithUnrecoverableAfterExhaustionWhenDeliveringSegment() {
    var session = startedSession();
    exhaustRecovery(session);

    var retry =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment0.ts");
    var drifted =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment3.ts");

    assertThat(retry).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(drifted).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should reopen recovery when a never-attempted target becomes eligible")
  void shouldReopenRecoveryWhenNeverAttemptedTargetBecomesEligible() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    exhaustRecovery(session);
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B, TARGET_C));

    var delivery = deliverAsync(sessionId, "segment0.ts");
    transcodeExecutor.awaitStartedTarget(TARGET_C);
    segmentStore.addSegment(sessionId, "segment0.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName(
      "Should treat a relocation-distance request as a planned seek restart after failure when delivering a segment")
  void shouldTreatRelocationDistanceRequestAsPlannedSeekRestartAfterFailureWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    exhaustRecovery(session);
    var targetedStartsBefore = transcodeExecutor.getStartedTargets().size();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(sessionId, "segment50.ts");
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    segmentStore.addSegment(sessionId, "segment50.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    // The seek revived the variant through positioning — a planned restart, not failed-window
    // recovery.
    assertThat(transcodeExecutor.getStartedTargets()).hasSize(targetedStartsBefore);
    var revival = transcodeExecutor.getStartedRequests().getLast();
    assertThat(revival.startSequenceNumber()).isEqualTo(50);
    assertThat(revival.seekPosition()).isEqualTo(300);
  }

  @Test
  @DisplayName("Should replace once then exhaust when runs complete without the advertised segment")
  void shouldReplaceOnceThenExhaustWhenRunsCompleteWithoutTheAdvertisedSegment() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    transcodeExecutor.markDead(sessionId);
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(sessionId, "segment2.ts");
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    // The replacement also completes without producing the advertised segment.
    transcodeExecutor.markDead(sessionId);

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore + 1);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should start exactly one replacement when two waiters observe one death")
  void shouldStartExactlyOneReplacementWhenTwoWaitersObserveOneDeath() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    transcodeExecutor.markDead(sessionId);
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var first = deliverAsync(sessionId, "segment0.ts");
    var second = deliverAsync(sessionId, "segment0.ts");
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    // Give the losing waiter its own recovery pass (superseded by the mutex predicate) before
    // publishing, so the "exactly one start" assertion covers the second waiter's attempt.
    awaitLivenessChecks(2);
    segmentStore.addSegment(sessionId, "segment0.ts", new byte[] {1});

    assertThat(first.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(second.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore + 1);
  }

  @Test
  @DisplayName("Should end the wait promptly when the session is destroyed")
  void shouldEndTheWaitPromptlyWhenTheSessionIsDestroyed() throws Exception {
    var session = startedSession();

    var delivery = deliverAsync(session.getSessionId(), "segment1.ts");
    // Let the delivery reach its wait loop, then remove the session; it must wake within one poll.
    awaitLivenessChecks(1);
    runtimeRegistry.removeById(session.getSessionId());

    assertThat(delivery.get(1, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName(
      "Should return cancelled and restore the interrupt without touching the producer when delivering a segment")
  void shouldReturnCancelledAndRestoreTheInterruptWithoutTouchingTheProducerWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    var outcome = new AtomicReference<SegmentDelivery>();
    var interruptRestored = new AtomicBoolean();
    var waiter =
        new Thread(
            () -> {
              outcome.set(
                  coordinator.deliver(
                      session.getSessionId(), StreamSession.defaultVariant(), "segment1.ts"));
              interruptRestored.set(Thread.currentThread().isInterrupted());
            });
    waiter.start();
    // The waiter is polling (in or between sleeps) once it has run a liveness check; interrupting
    // then must surface as Cancelled with the interrupt flag restored.
    awaitLivenessChecks(1);

    waiter.interrupt();
    waiter.join(2000);

    assertThat(outcome.get()).isInstanceOf(SegmentDelivery.Cancelled.class);
    assertThat(interruptRestored).isTrue();
    assertThat(transcodeExecutor.getStopped()).isEmpty();
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should map an init segment request to the current run's start sequence number when delivering a segment")
  void shouldMapInitSegmentRequestToTheCurrentRunsStartSequenceNumberWhenDeliveringSegment()
      throws Exception {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 12, 2);
    transcodeExecutor.markDead(session.getSessionId());
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "init.mp4");
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    segmentStore.addSegment(session.getSessionId(), "init.mp4", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    var replacement = transcodeExecutor.getStartedRequests().getLast();
    assertThat(replacement.startSequenceNumber()).isEqualTo(2);
    assertThat(replacement.seekPosition()).isEqualTo(12);
  }

  @Test
  @DisplayName("Should recover through execution targets when a suspended session fails to resume")
  void shouldRecoverThroughExecutionTargetsWhenSuspendedSessionFailsToResume() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    lifecycle.suspend(session.getSessionId());
    transcodeExecutor.failUntargetedStarts();

    var delivery = deliverAsync(sessionId, "segment1.ts");
    transcodeExecutor.awaitStartedTarget(ExecutionTargetId.LOCAL);
    segmentStore.addSegment(sessionId, "segment1.ts", new byte[] {1});

    // A failed resume enters recovery instead of escaping as a raw server error.
    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should exhaust to unrecoverable when a failed resume has no willing target")
  void shouldExhaustToUnrecoverableWhenFailedResumeHasNoWillingTarget() {
    var session = startedSession();
    lifecycle.suspend(session.getSessionId());
    transcodeExecutor.failUntargetedStarts();
    transcodeExecutor.refuseTarget(ExecutionTargetId.LOCAL);

    var delivery =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment1.ts");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should not kill a healthy replacement when a lagging waiter resumes its recovery")
  void shouldNotKillHealthyReplacementWhenLaggingWaiterResumesItsRecovery() throws Exception {
    var gatingExecutor = new EvidenceGatingExecutor();
    var rig = rigWith(gatingExecutor);
    gatingExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    rig.lifecycle().startAll(session, 0, 0);
    gatingExecutor.markDead(sessionId);

    // The lagging waiter observes the death and blocks entering recovery (pre-lock).
    gatingExecutor.blockFirstRecoveryEntry();
    var laggingWaiter =
        CompletableFuture.supplyAsync(
            () ->
                rig.coordinator()
                    .deliver(sessionId, StreamSession.defaultVariant(), "segment1.ts"));
    gatingExecutor.awaitFirstRecoveryEntry();

    // A second waiter completes the full recovery: healthy replacement Y on TARGET_A.
    var promptWaiter =
        CompletableFuture.supplyAsync(
            () ->
                rig.coordinator()
                    .deliver(sessionId, StreamSession.defaultVariant(), "segment1.ts"));
    gatingExecutor.awaitStartedTargetCount(1);
    awaitLivenessChecks(gatingExecutor, 1);
    var attemptY = session.getHandle().orElseThrow().attemptId();

    gatingExecutor.releaseRecoveryEntry();
    // The released lagging waiter runs its now-superseded recovery pass and returns to polling; a
    // couple of its poll cycles must go by without it starting a second target.
    awaitLivenessChecks(gatingExecutor, 2);

    // One death, one replacement: the healthy producer was neither stopped nor replaced again.
    assertThat(gatingExecutor.getStartedTargets()).containsExactly(TARGET_A);
    assertThat(gatingExecutor.getStoppedVariants()).isEmpty();
    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attemptY);

    segmentStore.addSegment(sessionId, "segment1.ts", new byte[] {1});
    assertThat(laggingWaiter.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(promptWaiter.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  @Test
  @DisplayName(
      "Should keep a seek revival healthy while an exhausting waiter races it when delivering a segment")
  void shouldKeepSeekRevivalHealthyWhileExhaustingWaiterRacesItWhenDeliveringSegment()
      throws Exception {
    var trapStore = new TrapSegmentStore();
    var rig = rigWith(transcodeExecutor, trapStore);
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    rig.lifecycle().startAll(session, 0, 0);

    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.refuseTarget(TARGET_A);
    transcodeExecutor.refuseTarget(TARGET_B);
    transcodeExecutor.markDead(sessionId);

    // The exhauster gets trapped between markExhausted (variant now FAILED) and its return, the
    // window where a racing revival must not be clobbered or wedged.
    var exhausterOutcome = new AtomicReference<SegmentDelivery>();
    var exhauster =
        new Thread(
            () ->
                exhausterOutcome.set(
                    rig.coordinator()
                        .deliver(sessionId, StreamSession.defaultVariant(), "segment0.ts")),
            "exhauster");
    trapStore.armTrap(
        exhauster, () -> session.getHandle().orElseThrow().status() == TranscodeStatus.FAILED);
    exhauster.start();
    assertThat(trapStore.reachedTrap.await(5, TimeUnit.SECONDS)).isTrue();

    // A planned seek revives the variant with a fresh attempt while the exhauster is trapped.
    var seekerOutcome = new AtomicReference<SegmentDelivery>();
    var seeker =
        new Thread(
            () ->
                seekerOutcome.set(
                    rig.coordinator()
                        .deliver(sessionId, StreamSession.defaultVariant(), "segment50.ts")),
            "seeker");
    var startsBefore = transcodeExecutor.getStartedRequests().size();
    seeker.start();
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    // The seeker revived the variant and now polls for segment50; let it settle into that wait
    // before interrupting so the outcome is a clean Cancelled.
    awaitLivenessChecks(1);
    seeker.interrupt();
    seeker.join(2000);
    assertThat(seekerOutcome.get()).isInstanceOf(SegmentDelivery.Cancelled.class);

    trapStore.releaseTrap.countDown();
    exhauster.join(2000);
    assertThat(exhausterOutcome.get()).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);

    // The revived attempt dies; targets now accept. The stale exhausted window must not survive:
    // a fresh recovery window opens and replaces the producer instead of spinning forever.
    transcodeExecutor.acceptTarget(TARGET_A);
    transcodeExecutor.acceptTarget(TARGET_B);
    transcodeExecutor.markDead(sessionId);
    var targetedStartsBefore = transcodeExecutor.getStartedTargets().size();

    var recovered =
        CompletableFuture.supplyAsync(
            () ->
                rig.coordinator()
                    .deliver(sessionId, StreamSession.defaultVariant(), "segment50.ts"));
    transcodeExecutor.awaitStartedTargetCount(targetedStartsBefore + 1);
    trapStore.addSegment(sessionId, "segment50.ts", new byte[] {1});

    assertThat(recovered.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  @Test
  @DisplayName("Should not resurrect a destroyed session when a replace races the destroy")
  void shouldNotResurrectDestroyedSessionWhenReplaceRacesTheDestroy() throws Exception {
    var gatingExecutor = new EvidenceGatingExecutor();
    var rig = rigWith(gatingExecutor);
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    rig.lifecycle().startAll(session, 0, 0);
    var attemptX = session.getHandle().orElseThrow().attemptId();
    gatingExecutor.markDead(sessionId);
    var streamingService =
        HlsStreamingService.builder()
            .transcodeExecutor(gatingExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .producerLifecycle(rig.lifecycle())
            .deliveryCoordinator(rig.coordinator())
            .build();

    gatingExecutor.holdTargetedStarts();
    var replace =
        CompletableFuture.supplyAsync(
            () ->
                rig.lifecycle()
                    .replaceProducer(
                        ProducerLifecycleService.ReplaceProducerCommand.builder()
                            .sessionId(sessionId)
                            .variantLabel(StreamSession.defaultVariant())
                            .segmentName("segment1.ts")
                            .segmentIndex(1)
                            .expectedAttemptId(attemptX)
                            .target(ExecutionTargetId.LOCAL)
                            .build()));
    gatingExecutor.awaitTargetedStartEntered();

    // Destroy must serialize with the in-flight replace instead of losing to its save. The latch
    // proves the destroy thread is running before the replace is released; the session mutex then
    // orders removal and save deterministically regardless of which reaches the lock first.
    var destroyStarted = new CountDownLatch(1);
    var destroy =
        CompletableFuture.runAsync(
            () -> {
              destroyStarted.countDown();
              streamingService.destroySession(sessionId);
            });
    assertThat(destroyStarted.await(5, TimeUnit.SECONDS)).isTrue();
    gatingExecutor.releaseTargetedStarts();
    replace.get(5, TimeUnit.SECONDS);
    destroy.get(5, TimeUnit.SECONDS);

    assertThat(runtimeRegistry.findById(sessionId)).isEmpty();
    assertThat(gatingExecutor.getStopped()).contains(sessionId);
  }

  private void exhaustRecovery(StreamSession session) {
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.refuseTarget(TARGET_A);
    transcodeExecutor.refuseTarget(TARGET_B);
    transcodeExecutor.markDead(session.getSessionId());
    var delivery =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment0.ts");
    assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  /** Blocks one specific thread at segmentExists while the armed condition holds, exactly once. */
  private static final class TrapSegmentStore extends FakeSegmentStore {

    private final CountDownLatch reachedTrap = new CountDownLatch(1);
    private final CountDownLatch releaseTrap = new CountDownLatch(1);
    private final AtomicBoolean tripped = new AtomicBoolean();
    private volatile Thread trappedThread;
    private volatile BooleanSupplier trapCondition;

    private void armTrap(Thread thread, BooleanSupplier condition) {
      trappedThread = thread;
      trapCondition = condition;
    }

    @Override
    public boolean segmentExists(UUID sessionId, String segmentName) {
      if (Thread.currentThread() == trappedThread
          && trapCondition.getAsBoolean()
          && tripped.compareAndSet(false, true)) {
        reachedTrap.countDown();
        try {
          if (!releaseTrap.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting to release trapped segment lookup");
          }
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
        }
      }
      return super.segmentExists(sessionId, segmentName);
    }
  }

  /**
   * Removes a session immediately after returning one lookup result, reproducing a destroy race.
   */
  private static final class DestroyAfterLookupRegistry extends FakeRuntimeStreamSessionRegistry {

    private Runnable destroyAction;

    private void destroyAfterNextLookup(Runnable action) {
      destroyAction = action;
    }

    @Override
    public Optional<StreamSession> findById(UUID sessionId) {
      var result = super.findById(sessionId);
      var action = destroyAction;
      if (action != null) {
        destroyAction = null;
        action.run();
      }
      return result;
    }
  }

  /** Gates recovery entry and targeted starts so races can be held open deterministically. */
  private static final class EvidenceGatingExecutor extends FakeTranscodeExecutor {

    private volatile CountDownLatch recoveryEntryGate;
    private final AtomicBoolean recoveryEntryGateTaken = new AtomicBoolean();
    private final CountDownLatch recoveryEntryEntered = new CountDownLatch(1);
    private volatile CountDownLatch targetedStartEntered;
    private volatile CountDownLatch targetedStartGate;

    private void blockFirstRecoveryEntry() {
      recoveryEntryGate = new CountDownLatch(1);
    }

    private void awaitFirstRecoveryEntry() throws InterruptedException {
      assertThat(recoveryEntryEntered.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private void releaseRecoveryEntry() {
      recoveryEntryGate.countDown();
    }

    private void holdTargetedStarts() {
      targetedStartEntered = new CountDownLatch(1);
      targetedStartGate = new CountDownLatch(1);
    }

    private void awaitTargetedStartEntered() throws InterruptedException {
      assertThat(targetedStartEntered.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private void releaseTargetedStarts() {
      targetedStartGate.countDown();
    }

    @Override
    public TranscodeHandle start(TranscodeRequest request, ExecutionTargetId target) {
      var entered = targetedStartEntered;
      if (entered != null) {
        entered.countDown();
        awaitQuietly(targetedStartGate);
      }
      return super.start(request, target);
    }

    @Override
    public Set<ExecutionTargetId> executionTargets() {
      var gate = recoveryEntryGate;
      if (gate != null && recoveryEntryGateTaken.compareAndSet(false, true)) {
        recoveryEntryEntered.countDown();
        awaitQuietly(gate);
      }
      return super.executionTargets();
    }

    private static void awaitQuietly(CountDownLatch latch) {
      try {
        latch.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Builder
  private record RaceLifecycleConfiguration(
      FakeTranscodeExecutor executor,
      FakeSegmentStore segmentStore,
      StreamingProperties properties,
      FakeRuntimeStreamSessionRegistry runtimeRegistry) {}

  /**
   * Inserts a planned restart after a real refusal returns but before the coordinator records it.
   */
  private static final class RefusalRaceLifecycle extends ProducerLifecycleService {

    private Runnable afterNextRefusal;

    private RefusalRaceLifecycle(RaceLifecycleConfiguration configuration) {
      super(
          configuration.executor(),
          configuration.segmentStore(),
          configuration.properties(),
          configuration.runtimeRegistry(),
          new MutexFactory<>());
    }

    private void afterNextRefusal(Runnable action) {
      afterNextRefusal = action;
    }

    @Override
    public ReplaceResult replaceProducer(ReplaceProducerCommand command) {
      var result = super.replaceProducer(command);
      if (!(result instanceof ReplaceResult.Refused) || afterNextRefusal == null) {
        return result;
      }

      var action = afterNextRefusal;
      afterNextRefusal = null;
      action.run();
      return result;
    }
  }

  /** Inserts a planned restart after a real replacement returns but before it is recorded. */
  private static final class ReplacementRaceLifecycle extends ProducerLifecycleService {

    private Runnable afterNextReplacement;

    private ReplacementRaceLifecycle(RaceLifecycleConfiguration configuration) {
      super(
          configuration.executor(),
          configuration.segmentStore(),
          configuration.properties(),
          configuration.runtimeRegistry(),
          new MutexFactory<>());
    }

    private void afterNextReplacement(Runnable action) {
      afterNextReplacement = action;
    }

    @Override
    public ReplaceResult replaceProducer(ReplaceProducerCommand command) {
      var result = super.replaceProducer(command);
      if (!(result instanceof ReplaceResult.Replaced) || afterNextReplacement == null) {
        return result;
      }

      var action = afterNextReplacement;
      afterNextReplacement = null;
      action.run();
      return result;
    }
  }
}
