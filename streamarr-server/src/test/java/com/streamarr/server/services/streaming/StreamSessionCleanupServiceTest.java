package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMediaSourceCatalog;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRepository;
import com.streamarr.server.services.streaming.worker.InspectJobQuery;
import com.streamarr.server.services.streaming.worker.InspectJobRejection;
import com.streamarr.server.services.streaming.worker.InspectJobResult;
import com.streamarr.server.services.streaming.worker.StartJobCommand;
import com.streamarr.server.services.streaming.worker.StartJobResult;
import com.streamarr.server.services.streaming.worker.StopJobCommand;
import com.streamarr.server.services.streaming.worker.StopJobResult;
import com.streamarr.server.services.streaming.worker.TranscodeWorkerPort;
import com.streamarr.server.services.streaming.worker.WorkerTarget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Stream Session Cleanup Service Tests")
class StreamSessionCleanupServiceTest {

  @Test
  @DisplayName("Should retain durable marker and wait while runtime starter is in flight")
  void shouldRetainDurableMarkerAndWaitWhileRuntimeStarterIsInFlight() throws Exception {
    var streamSessionId = UUID.randomUUID();
    var runtimeRegistry = new SignalingAwaitRegistry();
    var reservation = runtimeRegistry.reserve(streamSessionId).orElseThrow();
    var starter = runtimeRegistry.beginTranscodeStart(streamSessionId).orElseThrow();
    var lifecycle = new RecordingLifecycle();
    var segmentStore = new FakeSegmentStore();
    var worker = new SignalingAbsentStopWorker();
    var cleanupService =
        new StreamSessionCleanupService(
            runtimeService(runtimeRegistry, segmentStore, worker),
            lifecycle,
            segmentStore,
            new StreamSessionTransactionRetry(_ -> {}),
            new MutexFactoryProvider());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var cleanup = executor.submit(() -> cleanupService.cleanup(streamSessionId));

      assertThat(worker.awaitStop()).isTrue();
      assertThat(runtimeRegistry.awaitEntered()).isTrue();
      assertThat(lifecycle.deletedIds).isEmpty();

      runtimeRegistry.releaseReservation(reservation);
      assertThat(runtimeRegistry.completeTranscodeStart(starter)).isFalse();
      runtimeRegistry.finishRejectedTranscodeStart(starter, false);
      cleanup.get(5, TimeUnit.SECONDS);
    }

    assertThat(lifecycle.deletedIds).containsExactly(streamSessionId);
  }

  @Test
  @DisplayName("Should retain durable marker until failed external cleanup succeeds")
  void shouldRetainDurableMarkerUntilFailedExternalCleanupSucceeds() {
    var streamSessionId = UUID.randomUUID();
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    runtimeRegistry.save(StreamSession.builder().sessionId(streamSessionId).build());
    var segmentStore = new RetryableSegmentStore();
    var lifecycle = new RecordingLifecycle();
    var cleanupService =
        new StreamSessionCleanupService(
            runtimeService(runtimeRegistry, segmentStore),
            lifecycle,
            segmentStore,
            new StreamSessionTransactionRetry(_ -> {}),
            new MutexFactoryProvider());

    assertThatThrownBy(() -> cleanupService.cleanup(streamSessionId))
        .isInstanceOf(IllegalStateException.class);
    assertThat(lifecycle.deletedIds).isEmpty();

    segmentStore.allowDeletion();
    cleanupService.cleanup(streamSessionId);

    assertThat(lifecycle.deletedIds).containsExactly(streamSessionId);
  }

  @Test
  @DisplayName("Should reconcile runtime and stored sessions without durable authority")
  void shouldReconcileRuntimeAndStoredSessionsWithoutDurableAuthority() {
    var runtimeOnlyId = UUID.randomUUID();
    var storedOnlyId = UUID.randomUUID();
    var retainedId = UUID.randomUUID();
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    runtimeRegistry.save(StreamSession.builder().sessionId(runtimeOnlyId).build());
    var segmentStore = new FakeSegmentStore();
    segmentStore.addSegment(runtimeOnlyId, "runtime.ts", new byte[] {1});
    segmentStore.addSegment(storedOnlyId, "orphan.ts", new byte[] {2});
    segmentStore.addSegment(retainedId, "retained.ts", new byte[] {3});
    var lifecycle = new RecordingLifecycle(Set.of(retainedId));
    var cleanupService =
        new StreamSessionCleanupService(
            runtimeService(runtimeRegistry, segmentStore),
            lifecycle,
            segmentStore,
            new StreamSessionTransactionRetry(_ -> {}),
            new MutexFactoryProvider());

    cleanupService.reconcileUnbackedRuntimeAndStorage();

    assertThat(runtimeRegistry.findById(runtimeOnlyId)).isEmpty();
    assertThat(segmentStore.segmentExists(runtimeOnlyId, "runtime.ts")).isFalse();
    assertThat(segmentStore.segmentExists(storedOnlyId, "orphan.ts")).isFalse();
    assertThat(segmentStore.segmentExists(retainedId, "retained.ts")).isTrue();
  }

  @Test
  @DisplayName("Should delete nothing when durable session discovery fails")
  void shouldDeleteNothingWhenDurableSessionDiscoveryFails() {
    var runtimeId = UUID.randomUUID();
    var storedId = UUID.randomUUID();
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    runtimeRegistry.save(StreamSession.builder().sessionId(runtimeId).build());
    var segmentStore = new FakeSegmentStore();
    segmentStore.addSegment(runtimeId, "runtime.ts", new byte[] {1});
    segmentStore.addSegment(storedId, "stored.ts", new byte[] {2});
    var cleanupService =
        new StreamSessionCleanupService(
            runtimeService(runtimeRegistry, segmentStore),
            RecordingLifecycle.failingDiscovery(),
            segmentStore,
            new StreamSessionTransactionRetry(_ -> {}),
            new MutexFactoryProvider());

    assertThatThrownBy(cleanupService::reconcileUnbackedRuntimeAndStorage)
        .isInstanceOf(IllegalStateException.class);

    assertThat(runtimeRegistry.findById(runtimeId)).isPresent();
    assertThat(segmentStore.segmentExists(runtimeId, "runtime.ts")).isTrue();
    assertThat(segmentStore.segmentExists(storedId, "stored.ts")).isTrue();
  }

  @Test
  @DisplayName("Should continue stored orphan cleanup when one deletion fails")
  void shouldContinueStoredOrphanCleanupWhenOneDeletionFails() {
    var failingId = UUID.randomUUID();
    var succeedingId = UUID.randomUUID();
    var segmentStore = new FailingSegmentStore(failingId);
    segmentStore.addSegment(failingId, "failing.ts", new byte[] {1});
    segmentStore.addSegment(succeedingId, "succeeding.ts", new byte[] {2});
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    var cleanupService =
        new StreamSessionCleanupService(
            runtimeService(runtimeRegistry, segmentStore),
            new RecordingLifecycle(),
            segmentStore,
            new StreamSessionTransactionRetry(_ -> {}),
            new MutexFactoryProvider());

    cleanupService.reconcileUnbackedRuntimeAndStorage();

    assertThat(segmentStore.attemptedIds).containsExactlyInAnyOrder(failingId, succeedingId);
    assertThat(segmentStore.segmentExists(failingId, "failing.ts")).isTrue();
    assertThat(segmentStore.segmentExists(succeedingId, "succeeding.ts")).isFalse();
  }

  @Test
  @DisplayName("Should continue runtime-only cleanup when one termination fails")
  void shouldContinueRuntimeOnlyCleanupWhenOneTerminationFails() {
    var failingId = UUID.randomUUID();
    var succeedingId = UUID.randomUUID();
    var runtimeService = new FailingRuntimeService(List.of(failingId, succeedingId), failingId);
    var segmentStore = new FakeSegmentStore();
    var cleanupService =
        new StreamSessionCleanupService(
            runtimeService,
            new RecordingLifecycle(),
            segmentStore,
            new StreamSessionTransactionRetry(_ -> {}),
            new MutexFactoryProvider());

    cleanupService.reconcileUnbackedRuntimeAndStorage();

    assertThat(runtimeService.attemptedIds).containsExactlyInAnyOrder(failingId, succeedingId);
    assertThat(runtimeService.accessSession(failingId)).isPresent();
    assertThat(runtimeService.accessSession(succeedingId)).isEmpty();
  }

  @Test
  @DisplayName("Should retry runtime stop before deleting a fenced orphan directory")
  void shouldRetryRuntimeStopBeforeDeletingFencedOrphanDirectory() {
    var streamSessionId = UUID.randomUUID();
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    runtimeRegistry.save(StreamSession.builder().sessionId(streamSessionId).build());
    retainUncertainJob(runtimeRegistry, streamSessionId);
    var segmentStore = new FakeSegmentStore();
    segmentStore.addSegment(streamSessionId, "segment.ts", new byte[] {1});
    var transcodeWorker = new RetryableStopWorker();
    var cleanupService =
        new StreamSessionCleanupService(
            runtimeService(runtimeRegistry, segmentStore, transcodeWorker),
            new RecordingLifecycle(),
            segmentStore,
            new StreamSessionTransactionRetry(_ -> {}),
            new MutexFactoryProvider());

    cleanupService.reconcileUnbackedRuntimeAndStorage();

    assertThat(transcodeWorker.stopAttempts).isEqualTo(2);
    assertThat(segmentStore.segmentExists(streamSessionId, "segment.ts")).isTrue();

    cleanupService.reconcileUnbackedRuntimeAndStorage();

    assertThat(transcodeWorker.stopAttempts).isEqualTo(3);
    assertThat(segmentStore.segmentExists(streamSessionId, "segment.ts")).isFalse();
  }

  @Test
  @DisplayName("Should retry a fenced runtime stop without stored segments")
  void shouldRetryFencedRuntimeStopWithoutStoredSegments() {
    var streamSessionId = UUID.randomUUID();
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    runtimeRegistry.save(StreamSession.builder().sessionId(streamSessionId).build());
    retainUncertainJob(runtimeRegistry, streamSessionId);
    var segmentStore = new FakeSegmentStore();
    var transcodeWorker = new RetryableStopWorker();
    var cleanupService =
        new StreamSessionCleanupService(
            runtimeService(runtimeRegistry, segmentStore, transcodeWorker),
            new RecordingLifecycle(),
            segmentStore,
            new StreamSessionTransactionRetry(_ -> {}),
            new MutexFactoryProvider());

    cleanupService.reconcileUnbackedRuntimeAndStorage();
    cleanupService.reconcileUnbackedRuntimeAndStorage();

    assertThat(transcodeWorker.stopAttempts).isEqualTo(3);
  }

  private StreamingService runtimeService(
      RuntimeStreamSessionRegistry runtimeRegistry, SegmentStore segmentStore) {
    return runtimeService(runtimeRegistry, segmentStore, new AbsentStopWorker());
  }

  private StreamingService runtimeService(
      RuntimeStreamSessionRegistry runtimeRegistry,
      SegmentStore segmentStore,
      TranscodeWorkerPort worker) {
    var transcodeJobs =
        DefaultPlaybackTranscodeJobService.builder()
            .worker(worker)
            .workerTarget(new WorkerTarget(UUID.randomUUID(), UUID.randomUUID()))
            .runtimeRegistry(runtimeRegistry)
            .sessionMutexes(new MutexFactory<>())
            .build();
    return new HlsStreamingService(
        new FakeMediaFileRepository(),
        segmentStore,
        new FakeFfprobeService(),
        new TranscodeDecisionService(),
        new QualityLadderService(),
        StreamingProperties.builder().segmentDuration(Duration.ofSeconds(6)).build(),
        runtimeRegistry,
        new MutexFactory<>(),
        transcodeJobs,
        new FakeMediaSourceCatalog(),
        new TranscodeCapacityTracker());
  }

  private static void retainUncertainJob(
      RuntimeStreamSessionRegistry runtimeRegistry, UUID streamSessionId) {
    var start = runtimeRegistry.beginTranscodeStart(streamSessionId).orElseThrow();
    runtimeRegistry.abortTranscodeStart(start);
  }

  private static final class RecordingLifecycle
      extends UnsupportedStreamSessionLifecycleTransactions {

    private final List<UUID> deletedIds = new ArrayList<>();
    private final Set<UUID> existingIds;
    private final boolean discoveryFails;

    private RecordingLifecycle() {
      this(Set.of(), false);
    }

    private RecordingLifecycle(Set<UUID> existingIds) {
      this(existingIds, false);
    }

    private RecordingLifecycle(Set<UUID> existingIds, boolean discoveryFails) {
      this.existingIds = existingIds;
      this.discoveryFails = discoveryFails;
    }

    private static RecordingLifecycle failingDiscovery() {
      return new RecordingLifecycle(Set.of(), true);
    }

    @Override
    public Set<UUID> findAllSessionIds() {
      if (discoveryFails) {
        throw new IllegalStateException("simulated database failure");
      }
      return existingIds;
    }

    @Override
    public boolean deleteTerminating(UUID streamSessionId) {
      deletedIds.add(streamSessionId);
      return true;
    }
  }

  private static final class FailingSegmentStore extends FakeSegmentStore {

    private final UUID failingId;
    private final List<UUID> attemptedIds = new ArrayList<>();

    private FailingSegmentStore(UUID failingId) {
      this.failingId = failingId;
    }

    @Override
    public void deleteSession(UUID sessionId) {
      attemptedIds.add(sessionId);
      if (failingId.equals(sessionId)) {
        throw new IllegalStateException("simulated stored orphan cleanup failure");
      }
      super.deleteSession(sessionId);
    }
  }

  private static final class RetryableSegmentStore extends FakeSegmentStore {

    private boolean deletionFails = true;

    private void allowDeletion() {
      deletionFails = false;
    }

    @Override
    public void deleteSession(UUID sessionId) {
      if (deletionFails) {
        throw new IllegalStateException("simulated external cleanup failure");
      }
      super.deleteSession(sessionId);
    }
  }

  private static class AbsentStopWorker implements TranscodeWorkerPort {

    @Override
    public StartJobResult start(StartJobCommand command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StopJobResult stop(StopJobCommand command) {
      return new StopJobResult.AlreadyAbsent(command.jobRef());
    }

    @Override
    public InspectJobResult inspect(InspectJobQuery query) {
      return new InspectJobResult.Rejected(InspectJobRejection.TARGET_MISMATCH);
    }
  }

  private static final class RetryableStopWorker extends AbsentStopWorker {

    private int stopAttempts;

    @Override
    public StopJobResult stop(StopJobCommand command) {
      stopAttempts++;
      if (stopAttempts <= 2) {
        return new StopJobResult.CleanupPending(command.jobRef());
      }
      return super.stop(command);
    }
  }

  private static final class SignalingAbsentStopWorker extends AbsentStopWorker {

    private final CountDownLatch stopEntered = new CountDownLatch(1);

    @Override
    public StopJobResult stop(StopJobCommand command) {
      stopEntered.countDown();
      return super.stop(command);
    }

    private boolean awaitStop() throws InterruptedException {
      return stopEntered.await(5, TimeUnit.SECONDS);
    }
  }

  private static final class SignalingAwaitRegistry extends InMemoryStreamSessionRepository {

    private final CountDownLatch awaitEntered = new CountDownLatch(1);

    @Override
    public void awaitTranscodeStarts(UUID sessionId) {
      awaitEntered.countDown();
      super.awaitTranscodeStarts(sessionId);
    }

    private boolean awaitEntered() throws InterruptedException {
      return awaitEntered.await(5, TimeUnit.SECONDS);
    }
  }

  private static final class FailingRuntimeService implements StreamingService {

    private final LinkedHashMap<UUID, StreamSession> sessions = new LinkedHashMap<>();
    private final UUID failingId;
    private final List<UUID> attemptedIds = new ArrayList<>();

    private FailingRuntimeService(List<UUID> sessionIds, UUID failingId) {
      sessionIds.forEach(id -> sessions.put(id, StreamSession.builder().sessionId(id).build()));
      this.failingId = failingId;
    }

    @Override
    public StreamSession createSession(CreateRuntimeStreamSessionCommand command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StreamSession> accessSession(UUID sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void destroySession(UUID sessionId) {
      terminateRuntime(sessionId);
    }

    @Override
    public boolean terminateRuntime(UUID sessionId) {
      attemptedIds.add(sessionId);
      if (failingId.equals(sessionId)) {
        throw new IllegalStateException("simulated runtime-only cleanup failure");
      }
      sessions.remove(sessionId);
      return true;
    }

    @Override
    public void destroySession(UUID sessionId, UUID profileId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return List.copyOf(sessions.values());
    }

    @Override
    public int getActiveSessionCount() {
      return sessions.size();
    }

    @Override
    public void resumeSessionIfNeeded(UUID sessionId, String segmentName) {
      throw new UnsupportedOperationException();
    }
  }
}
