package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRepository;
import com.streamarr.server.services.streaming.worker.InspectJobQuery;
import com.streamarr.server.services.streaming.worker.InspectJobRejection;
import com.streamarr.server.services.streaming.worker.InspectJobResult;
import com.streamarr.server.services.streaming.worker.StartJobCommand;
import com.streamarr.server.services.streaming.worker.StartJobRejection;
import com.streamarr.server.services.streaming.worker.StartJobResult;
import com.streamarr.server.services.streaming.worker.StopJobCommand;
import com.streamarr.server.services.streaming.worker.StopJobRejection;
import com.streamarr.server.services.streaming.worker.StopJobResult;
import com.streamarr.server.services.streaming.worker.TranscodeWorkerPort;
import com.streamarr.server.services.streaming.worker.WorkerTarget;
import com.streamarr.transcode.engine.model.AudioDecision;
import com.streamarr.transcode.engine.model.ContainerFormat;
import com.streamarr.transcode.engine.model.MediaSourceRef;
import com.streamarr.transcode.engine.model.RenditionObservation;
import com.streamarr.transcode.engine.model.RenditionSpec;
import com.streamarr.transcode.engine.model.RenditionState;
import com.streamarr.transcode.engine.model.SubtitleDecision;
import com.streamarr.transcode.engine.model.TranscodeDecision;
import com.streamarr.transcode.engine.model.TranscodeExecutionParameters;
import com.streamarr.transcode.engine.model.TranscodeJobObservation;
import com.streamarr.transcode.engine.model.TranscodeJobState;
import com.streamarr.transcode.engine.model.TranscodeMode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Default Playback Transcode Job Service Tests")
class DefaultPlaybackTranscodeJobServiceTest {

  @Test
  @DisplayName("Should accept one running whole-ladder job")
  void shouldAcceptOneRunningWholeLadderJob() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);

    var observation = service.start(command(sessionId));

    assertThat(observation.state()).isEqualTo(TranscodeJobState.RUNNING);
    assertThat(observation.jobRef())
        .isEqualTo(registry.activeTranscodeJobRef(sessionId).orElseThrow());
    assertThat(registry.snapshotTranscodeJobRefs(sessionId)).containsExactly(observation.jobRef());
    assertThat(worker.startCommands())
        .singleElement()
        .satisfies(
            submitted -> {
              assertThat(submitted.target()).isEqualTo(worker.target());
              assertThat(submitted.specification().jobRef()).isEqualTo(observation.jobRef());
              assertThat(submitted.specification().renditions())
                  .isEqualTo(command(sessionId).renditions());
            });
  }

  @Test
  @DisplayName("Should inspect the exact active job with its absolute start number")
  void shouldInspectExactActiveJobWithItsAbsoluteStartNumber() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);
    var command = command(sessionId, 42);
    var started = service.start(command);

    worker.observe(started);

    assertThat(service.inspectActive(sessionId))
        .isEqualTo(new ActiveTranscodeJobInspection.Observed(started, 42));
  }

  @Test
  @DisplayName("Should report no active job when runtime has no published authority")
  void shouldReportNoActiveJobWhenRuntimeHasNoPublishedAuthority() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();

    assertThat(service(new FakeTranscodeWorker(), registry).inspectActive(sessionId))
        .isEqualTo(new ActiveTranscodeJobInspection.None());
  }

  @Test
  @DisplayName("Should fail closed while a submitted whole job is still unresolved")
  void shouldFailClosedWhileSubmittedWholeJobIsStillUnresolved() throws Exception {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    worker.blockFirstStart();
    var service = service(worker, registry);
    ActiveTranscodeJobInspection inspection;
    com.streamarr.transcode.engine.model.TranscodeJobRef submitted;

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = executor.submit(() -> service.start(command(sessionId)));
      assertThat(worker.awaitFirstStart()).isTrue();
      submitted = worker.startCommands().getFirst().specification().jobRef();
      inspection = service.inspectActive(sessionId);
      worker.releaseFirstStart();
      assertThat(start.get(5, TimeUnit.SECONDS).state()).isEqualTo(TranscodeJobState.RUNNING);
    }

    assertThat(inspection).isEqualTo(new ActiveTranscodeJobInspection.Unavailable(submitted));
  }

  @Test
  @DisplayName("Should fail closed when active job metadata is unavailable")
  void shouldFailClosedWhenActiveJobMetadataIsUnavailable() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var start = registry.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(registry.completeTranscodeStart(start)).isTrue();

    assertThat(service(new FakeTranscodeWorker(), registry).inspectActive(sessionId))
        .isEqualTo(new ActiveTranscodeJobInspection.Unavailable(start.jobRef()));
  }

  @Test
  @DisplayName("Should fail closed when worker inspection is rejected")
  void shouldFailClosedWhenWorkerInspectionIsRejected() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);
    var started = service.start(command(sessionId));

    assertThat(service.inspectActive(sessionId))
        .isEqualTo(new ActiveTranscodeJobInspection.Unavailable(started.jobRef()));
  }

  @Test
  @DisplayName("Should fail closed when worker inspection reports the wrong job")
  void shouldFailClosedWhenWorkerInspectionReportsWrongJob() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);
    var started = service.start(command(sessionId));
    worker.observe(
        TranscodeJobObservation.builder()
            .jobRef(new com.streamarr.transcode.engine.model.TranscodeJobRef(UUID.randomUUID(), 1))
            .state(TranscodeJobState.RUNNING)
            .renditions(List.of(new RenditionObservation("default", RenditionState.RUNNING)))
            .build());

    assertThat(service.inspectActive(sessionId))
        .isEqualTo(new ActiveTranscodeJobInspection.Unavailable(started.jobRef()));
  }

  @Test
  @DisplayName("Should fail closed when worker inspection throws")
  void shouldFailClosedWhenWorkerInspectionThrows() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);
    var started = service.start(command(sessionId));
    worker.failInspections();

    assertThat(service.inspectActive(sessionId))
        .isEqualTo(new ActiveTranscodeJobInspection.Unavailable(started.jobRef()));
  }

  @Test
  @DisplayName("Should reject invalid active inspection values")
  void shouldRejectInvalidActiveInspectionValues() {
    assertThatThrownBy(() -> new ActiveTranscodeJobInspection.Observed(null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    var absentObservation =
        TranscodeJobObservation.builder()
            .jobRef(new com.streamarr.transcode.engine.model.TranscodeJobRef(UUID.randomUUID(), 1))
            .state(TranscodeJobState.ABSENT)
            .renditions(List.of())
            .build();
    assertThatThrownBy(() -> new ActiveTranscodeJobInspection.Observed(absentObservation, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ActiveTranscodeJobInspection.Unavailable(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject missing or empty playback job values")
  void shouldRejectMissingOrEmptyPlaybackJobValues() {
    var valid = command(UUID.randomUUID());
    var nullRendition = new java.util.ArrayList<RenditionSpec>();
    nullRendition.add(null);

    assertThatThrownBy(
            () ->
                new StartPlaybackTranscodeJobCommand(
                    null, valid.source(), valid.decision(), valid.execution(), valid.renditions()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StartPlaybackTranscodeJobCommand(
                    valid.sessionId(),
                    null,
                    valid.decision(),
                    valid.execution(),
                    valid.renditions()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StartPlaybackTranscodeJobCommand(
                    valid.sessionId(), valid.source(), null, valid.execution(), valid.renditions()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StartPlaybackTranscodeJobCommand(
                    valid.sessionId(), valid.source(), valid.decision(), null, valid.renditions()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StartPlaybackTranscodeJobCommand(
                    valid.sessionId(), valid.source(), valid.decision(), valid.execution(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StartPlaybackTranscodeJobCommand(
                    valid.sessionId(),
                    valid.source(),
                    valid.decision(),
                    valid.execution(),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StartPlaybackTranscodeJobCommand(
                    valid.sessionId(),
                    valid.source(),
                    valid.decision(),
                    valid.execution(),
                    nullRendition))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should suspend every exact issued job including failed replacement authority")
  void shouldSuspendEveryExactIssuedJobIncludingFailedReplacementAuthority() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);
    var fallback = service.start(command(sessionId)).jobRef();
    worker.rejectStartsWith(StartJobRejection.STARTUP_FAILED);
    var replacementCommand = command(sessionId);
    assertThatThrownBy(() -> service.start(replacementCommand))
        .isInstanceOf(TranscodeJobStartException.class);
    var failedReplacement = worker.startCommands().getLast().specification().jobRef();

    assertThat(service.suspend(sessionId)).isEqualTo(RuntimeTranscodeCleanup.COMPLETE);

    assertThat(worker.stopCommands())
        .extracting(StopJobCommand::jobRef)
        .containsExactly(fallback, failedReplacement);
    assertThat(registry.snapshotTranscodeJobRefs(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should retry typed cleanup-pending result with a new command identity")
  void shouldRetryTypedCleanupPendingResultWithNewCommandIdentity() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);
    var jobRef = service.start(command(sessionId)).jobRef();
    worker.stopInSequence(StopOutcome.CLEANUP_PENDING, StopOutcome.STOPPED);

    assertThat(service.suspend(sessionId)).isEqualTo(RuntimeTranscodeCleanup.COMPLETE);

    assertThat(worker.stopCommands())
        .hasSize(2)
        .allSatisfy(stop -> assertThat(stop.jobRef()).isEqualTo(jobRef));
    assertThat(worker.stopCommands().get(0).commandId())
        .isNotEqualTo(worker.stopCommands().get(1).commandId());
  }

  @Test
  @DisplayName("Should continue draining other exact jobs when one stop remains uncertain")
  void shouldContinueDrainingOtherExactJobsWhenOneStopRemainsUncertain() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);
    var fallback = service.start(command(sessionId)).jobRef();
    worker.rejectStartsWith(StartJobRejection.STARTUP_FAILED);
    var replacementCommand = command(sessionId);
    assertThatThrownBy(() -> service.start(replacementCommand))
        .isInstanceOf(TranscodeJobStartException.class);
    var failedReplacement = worker.startCommands().getLast().specification().jobRef();
    worker.stopWith(fallback, StopOutcome.FAILURE);

    assertThat(service.suspend(sessionId)).isEqualTo(RuntimeTranscodeCleanup.PENDING);

    assertThat(worker.stopCommands())
        .extracting(StopJobCommand::jobRef)
        .containsExactly(fallback, failedReplacement, fallback);
    assertThat(worker.stopCommands().getFirst().commandId())
        .isEqualTo(worker.stopCommands().getLast().commandId());
    assertThat(registry.snapshotTranscodeJobRefs(sessionId)).containsExactly(fallback);
  }

  @Test
  @DisplayName("Should drain fallback before failed high-water regardless of registry order")
  void shouldDrainFallbackBeforeFailedHighWaterRegardlessOfRegistryOrder() {
    var registry = new ReversingSnapshotRegistry();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);
    var fallback = service.start(command(sessionId)).jobRef();
    worker.rejectStartsWith(StartJobRejection.STARTUP_FAILED);
    var replacementCommand = command(sessionId);
    assertThatThrownBy(() -> service.start(replacementCommand))
        .isInstanceOf(TranscodeJobStartException.class);
    var failedHighWater = worker.startCommands().getLast().specification().jobRef();

    assertThat(service.suspend(sessionId)).isEqualTo(RuntimeTranscodeCleanup.COMPLETE);

    assertThat(worker.stopCommands())
        .extracting(StopJobCommand::jobRef)
        .containsExactly(fallback, failedHighWater);
  }

  @Test
  @DisplayName("Should wait for a late starter and drain it without taking the session mutex")
  void shouldWaitForLateStarterAndDrainItWithoutTakingSessionMutex() throws Exception {
    var registry = new SignalingAwaitRegistry();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    worker.blockFirstStart();
    var service = service(worker, registry);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = executor.submit(() -> service.start(command(sessionId)));
      assertThat(worker.awaitFirstStart()).isTrue();
      registry.terminalize(sessionId);
      var cleanup = executor.submit(() -> service.cleanupTerminal(sessionId));

      assertThat(registry.awaitEntered()).isTrue();
      worker.releaseFirstStart();

      assertThatThrownBy(() -> start.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);
      assertThat(cleanup.get(5, TimeUnit.SECONDS)).isEqualTo(RuntimeTranscodeCleanup.COMPLETE);
    }

    assertThat(registry.snapshotTranscodeJobRefs(sessionId)).isEmpty();
    assertThat(worker.stopCommands())
        .extracting(StopJobCommand::jobRef)
        .containsOnly(worker.startCommands().getFirst().specification().jobRef());
  }

  @Test
  @DisplayName("Should reject start before contacting worker when runtime slot is absent")
  void shouldRejectStartBeforeContactingWorkerWhenRuntimeSlotIsAbsent() {
    var registry = new InMemoryStreamSessionRepository();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);

    assertThatThrownBy(() -> service.start(command(UUID.randomUUID())))
        .isInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);

    assertThat(worker.startCommands()).isEmpty();
    assertThat(worker.stopCommands()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = StartJobRejection.class,
      names = {
        "TARGET_MISMATCH",
        "STALE_GENERATION",
        "CAPACITY_EXHAUSTED",
        "SOURCE_UNAVAILABLE",
        "INVALID_SPECIFICATION",
        "SHUTTING_DOWN"
      })
  @DisplayName("Should release a submitted job when rejection proves it never started")
  void shouldReleaseSubmittedJobWhenRejectionProvesItNeverStarted(StartJobRejection rejection) {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    worker.rejectStartsWith(rejection);
    var service = service(worker, registry);
    var startCommand = command(sessionId);

    var thrown =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> service.start(startCommand), TranscodeJobStartException.class);

    assertThat(thrown.jobRef())
        .isEqualTo(worker.startCommands().getFirst().specification().jobRef());
    assertThat(thrown.result()).isEqualTo(new StartJobResult.Rejected(rejection));
    assertThat(registry.snapshotTranscodeJobRefs(sessionId)).isEmpty();
    assertThat(registry.activeTranscodeJobRef(sessionId)).isEmpty();
    assertThat(worker.startCommands()).hasSize(1);
    assertThat(worker.stopCommands()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = StopOutcome.class,
      names = {"STOPPED", "ALREADY_ABSENT"})
  @DisplayName("Should stop and settle the exact accepted job when the terminal fence wins")
  void shouldStopAndSettleExactAcceptedJobWhenTerminalFenceWins(StopOutcome outcome) {
    var registry = new RejectingCompletionRegistry();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    worker.stopWith(outcome);
    var service = service(worker, registry);

    assertThatThrownBy(() -> service.start(command(sessionId)))
        .isInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);

    var submitted = worker.startCommands().getFirst().specification().jobRef();
    assertThat(worker.stopCommands())
        .singleElement()
        .satisfies(stop -> assertThat(stop.jobRef()).isEqualTo(submitted));
    assertThat(registry.snapshotTranscodeJobRefs(sessionId)).isEmpty();
    assertThat(registry.activeTranscodeJobRef(sessionId)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = StopOutcome.class,
      names = {"CLEANUP_PENDING", "REJECTED", "MISMATCH", "FAILURE"})
  @DisplayName("Should retain accepted job when terminal-fence stop remains uncertain")
  void shouldRetainAcceptedJobWhenTerminalFenceStopRemainsUncertain(StopOutcome outcome) {
    var registry = new RejectingCompletionRegistry();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    worker.stopWith(outcome);
    var service = service(worker, registry);

    assertThatThrownBy(() -> service.start(command(sessionId)))
        .isInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);

    var submitted = worker.startCommands().getFirst().specification().jobRef();
    assertThat(worker.stopCommands())
        .singleElement()
        .satisfies(stop -> assertThat(stop.jobRef()).isEqualTo(submitted));
    assertThat(registry.snapshotTranscodeJobRefs(sessionId)).containsExactly(submitted);
    assertThat(registry.activeTranscodeJobRef(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should serialize whole-job replacement settlement per session")
  void shouldSerializeWholeJobReplacementSettlementPerSession() throws Exception {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    worker.blockFirstStart();
    var mutexes = new SignalingMutexFactory();
    var service = service(worker, registry, mutexes);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> service.start(command(sessionId)));
      assertThat(worker.awaitFirstStart()).isTrue();
      var second = executor.submit(() -> service.start(command(sessionId)));

      assertThat(mutexes.awaitSecondLockAttempt()).isTrue();
      assertThat(worker.startCommands()).hasSize(1);
      worker.releaseFirstStart();

      assertThat(first.get(5, TimeUnit.SECONDS).jobRef().generation()).isEqualTo(1);
      assertThat(second.get(5, TimeUnit.SECONDS).jobRef().generation()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("Should keep shutdown waiting until accepted late start is stopped")
  void shouldKeepShutdownWaitingUntilAcceptedLateStartIsStopped() throws Exception {
    var registry = new SignalingAwaitRegistry();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    worker.blockFirstStart();
    var service = service(worker, registry);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = executor.submit(() -> service.start(command(sessionId)));
      assertThat(worker.awaitFirstStart()).isTrue();
      var shutdown =
          executor.submit(
              () -> {
                registry.fenceAll();
                registry.awaitTranscodeStarts(sessionId);
              });

      assertThat(registry.awaitEntered()).isTrue();
      worker.releaseFirstStart();

      assertThatThrownBy(() -> start.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);
      shutdown.get(5, TimeUnit.SECONDS);
    }

    assertThat(worker.stopCommands())
        .singleElement()
        .satisfies(
            stop ->
                assertThat(stop.jobRef())
                    .isEqualTo(worker.startCommands().getFirst().specification().jobRef()));
  }

  @ParameterizedTest
  @EnumSource(
      value = StartJobRejection.class,
      names = {"COMMAND_CONFLICT", "JOB_CONFLICT", "STARTUP_FAILED"})
  @DisplayName("Should retain an unresolved failed high-water without stopping the fallback")
  void shouldRetainUnresolvedFailedHighWaterWithoutStoppingFallback(StartJobRejection rejection) {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);
    var fallback = service.start(command(sessionId)).jobRef();
    worker.rejectStartsWith(rejection);
    var replacementCommand = command(sessionId);

    var thrown =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> service.start(replacementCommand), TranscodeJobStartException.class);

    var failed = worker.startCommands().getLast().specification().jobRef();
    assertThat(thrown.result()).isEqualTo(new StartJobResult.Rejected(rejection));
    assertThat(failed.generation()).isEqualTo(2);
    assertThat(registry.activeTranscodeJobRef(sessionId)).contains(fallback);
    assertThat(registry.snapshotTranscodeJobRefs(sessionId)).containsExactly(fallback, failed);
    assertThat(worker.stopCommands()).isEmpty();
  }

  @Test
  @DisplayName("Should retain cleanup-pending high-water without stopping the fallback")
  void shouldRetainCleanupPendingHighWaterWithoutStoppingFallback() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);
    var fallback = service.start(command(sessionId)).jobRef();
    worker.returnCleanupPending();
    var replacementCommand = command(sessionId);

    var thrown =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> service.start(replacementCommand), TranscodeJobStartException.class);

    var failed = worker.startCommands().getLast().specification().jobRef();
    assertThat(thrown.result()).isEqualTo(new StartJobResult.CleanupPending(failed));
    assertThat(registry.activeTranscodeJobRef(sessionId)).contains(fallback);
    assertThat(registry.snapshotTranscodeJobRefs(sessionId)).containsExactly(fallback, failed);
    assertThat(worker.stopCommands()).isEmpty();
  }

  @Test
  @DisplayName("Should accept a whole-ladder job that completes during startup")
  void shouldAcceptWholeLadderJobThatCompletesDuringStartup() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    worker.completeStartsImmediately();
    var service = service(worker, registry);

    var observation = service.start(command(sessionId));

    assertThat(observation.state()).isEqualTo(TranscodeJobState.COMPLETED);
    assertThat(registry.activeTranscodeJobRef(sessionId)).contains(observation.jobRef());
  }

  @Test
  @DisplayName("Should retain submitted high-water when the worker reports the wrong reference")
  void shouldRetainSubmittedHighWaterWhenWorkerReportsWrongReference() {
    var fixture = replacementFixture();
    fixture.worker().acceptStartsWithWrongReference();
    var service = fixture.service();
    var replacementCommand = command(fixture.sessionId());

    assertThatThrownBy(() -> service.start(replacementCommand))
        .isInstanceOf(TranscodeJobStartException.class);

    assertRetainedFailedHighWater(fixture);
  }

  @Test
  @DisplayName("Should retain submitted high-water when accepted state is not settled")
  void shouldRetainSubmittedHighWaterWhenAcceptedStateIsNotSettled() {
    var fixture = replacementFixture();
    fixture.worker().acceptStartsAsFailed();
    var service = fixture.service();
    var replacementCommand = command(fixture.sessionId());

    assertThatThrownBy(() -> service.start(replacementCommand))
        .isInstanceOf(TranscodeJobStartException.class);

    assertRetainedFailedHighWater(fixture);
  }

  @Test
  @DisplayName("Should retain submitted high-water and propagate worker failure")
  void shouldRetainSubmittedHighWaterAndPropagateWorkerFailure() {
    var fixture = replacementFixture();
    var failure = new IllegalStateException("worker response lost");
    fixture.worker().failStartsWith(failure);
    var service = fixture.service();
    var replacementCommand = command(fixture.sessionId());

    assertThatThrownBy(() -> service.start(replacementCommand)).isSameAs(failure);

    assertRetainedFailedHighWater(fixture);
  }

  @ParameterizedTest
  @EnumSource(
      value = StopOutcome.class,
      names = {"STOPPED", "ALREADY_ABSENT"})
  @DisplayName("Should release an exact superseded job after replacement succeeds")
  void shouldReleaseExactSupersededJobAfterReplacementSucceeds(StopOutcome outcome) {
    var fixture = replacementFixture();
    fixture.worker().stopWith(outcome);

    var replacement = fixture.service().start(command(fixture.sessionId())).jobRef();

    assertThat(fixture.worker().stopCommands())
        .singleElement()
        .satisfies(stop -> assertThat(stop.jobRef()).isEqualTo(fixture.fallback()));
    assertThat(fixture.registry().activeTranscodeJobRef(fixture.sessionId())).contains(replacement);
    assertThat(fixture.registry().snapshotTranscodeJobRefs(fixture.sessionId()))
        .containsExactly(replacement);
  }

  @ParameterizedTest
  @EnumSource(
      value = StopOutcome.class,
      names = {"CLEANUP_PENDING", "REJECTED", "MISMATCH", "FAILURE"})
  @DisplayName("Should retain superseded job when exact absence is not proven")
  void shouldRetainSupersededJobWhenExactAbsenceIsNotProven(StopOutcome outcome) {
    var fixture = replacementFixture();
    fixture.worker().stopWith(outcome);

    var replacement = fixture.service().start(command(fixture.sessionId())).jobRef();

    assertThat(fixture.registry().activeTranscodeJobRef(fixture.sessionId())).contains(replacement);
    assertThat(fixture.registry().snapshotTranscodeJobRefs(fixture.sessionId()))
        .containsExactly(fixture.fallback(), replacement);
  }

  @Test
  @DisplayName("Should not clean fallback when replacement loses the terminal fence")
  void shouldNotCleanFallbackWhenReplacementLosesTerminalFence() {
    var registry = new RejectingSecondCompletionRegistry();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);
    var fallback = service.start(command(sessionId)).jobRef();
    var replacementCommand = command(sessionId);

    assertThatThrownBy(() -> service.start(replacementCommand))
        .isInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);

    var rejectedReplacement = worker.startCommands().getLast().specification().jobRef();
    assertThat(worker.stopCommands())
        .singleElement()
        .satisfies(stop -> assertThat(stop.jobRef()).isEqualTo(rejectedReplacement));
    assertThat(registry.activeTranscodeJobRef(sessionId)).contains(fallback);
    assertThat(registry.snapshotTranscodeJobRefs(sessionId)).containsExactly(fallback);
  }

  private static StartPlaybackTranscodeJobCommand command(UUID sessionId) {
    return command(sessionId, 0);
  }

  private static StartPlaybackTranscodeJobCommand command(UUID sessionId, int startNumber) {
    return StartPlaybackTranscodeJobCommand.builder()
        .sessionId(sessionId)
        .source(new MediaSourceRef(UUID.randomUUID(), "movies/test.mkv"))
        .decision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                .videoCodecFamily("h264")
                .audioDecision(AudioDecision.stereoAac())
                .subtitleDecision(SubtitleDecision.exclude())
                .containerFormat(ContainerFormat.MPEGTS)
                .build())
        .execution(
            TranscodeExecutionParameters.builder()
                .seekPosition(0)
                .segmentDuration(6)
                .framerate(23.976)
                .startNumber(startNumber)
                .startupTimeout(Duration.ofSeconds(45))
                .build())
        .renditions(
            List.of(
                new RenditionSpec("1080p", 1920, 1080, 5_000_000L),
                new RenditionSpec("720p", 1280, 720, 3_000_000L)))
        .build();
  }

  private static DefaultPlaybackTranscodeJobService service(
      FakeTranscodeWorker worker, RuntimeStreamSessionRegistry registry) {
    return service(worker, registry, new MutexFactory<>());
  }

  private static DefaultPlaybackTranscodeJobService service(
      FakeTranscodeWorker worker,
      RuntimeStreamSessionRegistry registry,
      MutexFactory<UUID> sessionMutexes) {
    return DefaultPlaybackTranscodeJobService.builder()
        .worker(worker)
        .workerTarget(worker.target())
        .runtimeRegistry(registry)
        .sessionMutexes(sessionMutexes)
        .build();
  }

  private static ReplacementFixture replacementFixture() {
    var registry = new InMemoryStreamSessionRepository();
    var sessionId = UUID.randomUUID();
    registry.reserve(sessionId).orElseThrow();
    var worker = new FakeTranscodeWorker();
    var service = service(worker, registry);
    var fallback = service.start(command(sessionId)).jobRef();
    return new ReplacementFixture(registry, sessionId, worker, service, fallback);
  }

  private static void assertRetainedFailedHighWater(ReplacementFixture fixture) {
    var failed = fixture.worker().startCommands().getLast().specification().jobRef();
    assertThat(fixture.registry().activeTranscodeJobRef(fixture.sessionId()))
        .contains(fixture.fallback());
    assertThat(fixture.registry().snapshotTranscodeJobRefs(fixture.sessionId()))
        .containsExactly(fixture.fallback(), failed);
    assertThat(fixture.worker().stopCommands()).isEmpty();
  }

  private record ReplacementFixture(
      InMemoryStreamSessionRepository registry,
      UUID sessionId,
      FakeTranscodeWorker worker,
      DefaultPlaybackTranscodeJobService service,
      com.streamarr.transcode.engine.model.TranscodeJobRef fallback) {}

  private enum StopOutcome {
    STOPPED,
    ALREADY_ABSENT,
    CLEANUP_PENDING,
    REJECTED,
    MISMATCH,
    FAILURE
  }

  private static final class FakeTranscodeWorker implements TranscodeWorkerPort {

    private final WorkerTarget target = new WorkerTarget(UUID.randomUUID(), UUID.randomUUID());
    private final java.util.concurrent.CopyOnWriteArrayList<StartJobCommand> starts =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<StopJobCommand> stops =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private java.util.function.Function<StartJobCommand, StartJobResult> startResult =
        FakeTranscodeWorker::acceptedRunning;
    private java.util.function.Function<StopJobCommand, StopJobResult> stopResult =
        submitted -> new StopJobResult.Stopped(submitted.jobRef());
    private final java.util.concurrent.ConcurrentHashMap<
            com.streamarr.transcode.engine.model.TranscodeJobRef, StopOutcome>
        stopOutcomesByJobRef = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.function.Function<InspectJobQuery, InspectJobResult> inspectResult =
        _ -> new InspectJobResult.Rejected(InspectJobRejection.TARGET_MISMATCH);
    private final AtomicInteger startAttempts = new AtomicInteger();
    private volatile CountDownLatch firstStartEntered;
    private volatile CountDownLatch allowFirstStart;

    private WorkerTarget target() {
      return target;
    }

    private List<StartJobCommand> startCommands() {
      return List.copyOf(starts);
    }

    private List<StopJobCommand> stopCommands() {
      return List.copyOf(stops);
    }

    private void rejectStartsWith(StartJobRejection rejection) {
      startResult = _ -> new StartJobResult.Rejected(rejection);
    }

    private void returnCleanupPending() {
      startResult =
          submitted -> new StartJobResult.CleanupPending(submitted.specification().jobRef());
    }

    private void completeStartsImmediately() {
      startResult = submitted -> accepted(submitted, TranscodeJobState.COMPLETED);
    }

    private void acceptStartsWithWrongReference() {
      startResult =
          submitted ->
              accepted(
                  submitted,
                  new com.streamarr.transcode.engine.model.TranscodeJobRef(UUID.randomUUID(), 1),
                  TranscodeJobState.RUNNING);
    }

    private void acceptStartsAsFailed() {
      startResult = submitted -> accepted(submitted, TranscodeJobState.FAILED);
    }

    private void failStartsWith(RuntimeException failure) {
      startResult =
          _ -> {
            throw failure;
          };
    }

    private void stopWith(StopOutcome outcome) {
      stopResult = submitted -> stopResult(outcome, submitted);
    }

    private void stopWith(
        com.streamarr.transcode.engine.model.TranscodeJobRef jobRef, StopOutcome outcome) {
      stopOutcomesByJobRef.put(jobRef, outcome);
    }

    private void stopInSequence(StopOutcome... outcomes) {
      var remaining = new java.util.concurrent.ConcurrentLinkedQueue<>(List.of(outcomes));
      stopResult = submitted -> stopResult(remaining.remove(), submitted);
    }

    private static StopJobResult stopResult(StopOutcome outcome, StopJobCommand submitted) {
      return switch (outcome) {
        case STOPPED -> new StopJobResult.Stopped(submitted.jobRef());
        case ALREADY_ABSENT -> new StopJobResult.AlreadyAbsent(submitted.jobRef());
        case CLEANUP_PENDING -> new StopJobResult.CleanupPending(submitted.jobRef());
        case REJECTED -> new StopJobResult.Rejected(StopJobRejection.STALE_GENERATION);
        case MISMATCH ->
            new StopJobResult.Stopped(
                new com.streamarr.transcode.engine.model.TranscodeJobRef(UUID.randomUUID(), 1));
        case FAILURE -> throw new IllegalStateException("stop response lost");
      };
    }

    private void observe(TranscodeJobObservation observation) {
      inspectResult = _ -> new InspectJobResult.Observed(observation);
    }

    private void failInspections() {
      inspectResult =
          _ -> {
            throw new IllegalStateException("inspection unavailable");
          };
    }

    private void blockFirstStart() {
      firstStartEntered = new CountDownLatch(1);
      allowFirstStart = new CountDownLatch(1);
    }

    private boolean awaitFirstStart() throws InterruptedException {
      return firstStartEntered.await(5, TimeUnit.SECONDS);
    }

    private void releaseFirstStart() {
      allowFirstStart.countDown();
    }

    @Override
    public StartJobResult start(StartJobCommand command) {
      starts.add(command);
      var attempt = startAttempts.incrementAndGet();
      if (attempt == 1 && firstStartEntered != null) {
        firstStartEntered.countDown();
        await(allowFirstStart);
      }
      return startResult.apply(command);
    }

    private static void await(CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(exception);
      }
    }

    private static StartJobResult acceptedRunning(StartJobCommand command) {
      return accepted(command, TranscodeJobState.RUNNING);
    }

    private static StartJobResult accepted(StartJobCommand command, TranscodeJobState state) {
      return accepted(command, command.specification().jobRef(), state);
    }

    private static StartJobResult accepted(
        StartJobCommand command,
        com.streamarr.transcode.engine.model.TranscodeJobRef jobRef,
        TranscodeJobState state) {
      var specification = command.specification();
      return new StartJobResult.Accepted(
          TranscodeJobObservation.builder()
              .jobRef(jobRef)
              .state(state)
              .renditions(
                  specification.renditions().stream()
                      .map(
                          rendition ->
                              new RenditionObservation(rendition.label(), renditionState(state)))
                      .toList())
              .build());
    }

    private static RenditionState renditionState(TranscodeJobState state) {
      return switch (state) {
        case RUNNING -> RenditionState.RUNNING;
        case COMPLETED -> RenditionState.COMPLETED;
        case FAILED -> RenditionState.FAILED;
        default -> throw new IllegalArgumentException("Unsupported fake state " + state);
      };
    }

    @Override
    public StopJobResult stop(StopJobCommand command) {
      stops.add(command);
      var exactOutcome = stopOutcomesByJobRef.get(command.jobRef());
      if (exactOutcome != null) {
        return stopResult(exactOutcome, command);
      }
      return stopResult.apply(command);
    }

    @Override
    public InspectJobResult inspect(InspectJobQuery query) {
      return inspectResult.apply(query);
    }
  }

  private static final class RejectingCompletionRegistry extends InMemoryStreamSessionRepository {

    @Override
    public boolean completeTranscodeStart(RuntimeTranscodeStart start) {
      return false;
    }
  }

  private static final class RejectingSecondCompletionRegistry
      extends InMemoryStreamSessionRepository {

    private int completions;

    @Override
    public boolean completeTranscodeStart(RuntimeTranscodeStart start) {
      if (++completions == 2) {
        return false;
      }
      return super.completeTranscodeStart(start);
    }
  }

  private static final class ReversingSnapshotRegistry extends InMemoryStreamSessionRepository {

    @Override
    public List<com.streamarr.transcode.engine.model.TranscodeJobRef> snapshotTranscodeJobRefs(
        UUID sessionId) {
      var reversed = new java.util.ArrayList<>(super.snapshotTranscodeJobRefs(sessionId));
      java.util.Collections.reverse(reversed);
      return List.copyOf(reversed);
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

  private static final class SignalingMutexFactory extends MutexFactory<UUID> {

    private final CountDownLatch secondLockAttempted = new CountDownLatch(1);
    private final AtomicInteger lockAttempts = new AtomicInteger();
    private final java.util.concurrent.locks.ReentrantLock mutex =
        new java.util.concurrent.locks.ReentrantLock() {
          @Override
          public void lock() {
            if (lockAttempts.incrementAndGet() == 2) {
              secondLockAttempted.countDown();
            }
            super.lock();
          }
        };

    @Override
    public java.util.concurrent.locks.ReentrantLock getMutex(UUID key) {
      return mutex;
    }

    private boolean awaitSecondLockAttempt() throws InterruptedException {
      return secondLockAttempted.await(5, TimeUnit.SECONDS);
    }
  }
}
