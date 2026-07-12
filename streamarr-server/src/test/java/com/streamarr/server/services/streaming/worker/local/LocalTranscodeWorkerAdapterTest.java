package com.streamarr.server.services.streaming.worker.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.services.streaming.source.MediaSourceCatalog;
import com.streamarr.server.services.streaming.source.MediaSourceUnavailableException;
import com.streamarr.server.services.streaming.worker.InspectJobQuery;
import com.streamarr.server.services.streaming.worker.InspectJobRejection;
import com.streamarr.server.services.streaming.worker.InspectJobResult;
import com.streamarr.server.services.streaming.worker.StartJobCommand;
import com.streamarr.server.services.streaming.worker.StartJobRejection;
import com.streamarr.server.services.streaming.worker.StartJobResult;
import com.streamarr.server.services.streaming.worker.StopJobCommand;
import com.streamarr.server.services.streaming.worker.StopJobRejection;
import com.streamarr.server.services.streaming.worker.StopJobResult;
import com.streamarr.server.services.streaming.worker.WorkerTarget;
import com.streamarr.transcode.engine.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessKey;
import com.streamarr.transcode.engine.ffmpeg.TranscodeCapabilityService;
import com.streamarr.transcode.engine.job.LocalTranscodeEngine;
import com.streamarr.transcode.engine.job.TranscodeEngineException;
import com.streamarr.transcode.engine.model.AudioDecision;
import com.streamarr.transcode.engine.model.ContainerFormat;
import com.streamarr.transcode.engine.model.MediaSourceRef;
import com.streamarr.transcode.engine.model.RenditionSpec;
import com.streamarr.transcode.engine.model.SubtitleDecision;
import com.streamarr.transcode.engine.model.TranscodeDecision;
import com.streamarr.transcode.engine.model.TranscodeExecutionParameters;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import com.streamarr.transcode.engine.model.TranscodeJobSpec;
import com.streamarr.transcode.engine.model.TranscodeJobState;
import com.streamarr.transcode.engine.model.TranscodeMode;
import com.streamarr.transcode.engine.segment.LocalSegmentStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Local Transcode Worker Adapter Tests")
class LocalTranscodeWorkerAdapterTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should reject a mismatched start target before resolving or starting the source")
  void shouldRejectMismatchedStartTargetBeforeResolvingOrStartingSource() {
    var target = workerTarget();
    var processManager = new FakeFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var command = startCommand(workerTarget(), jobSpecification());

    var result = adapter.start(command);

    assertThat(result).isEqualTo(new StartJobResult.Rejected(StartJobRejection.TARGET_MISMATCH));
    assertThat(catalog.resolvedSources()).isEmpty();
    assertThat(processManager.getStarted()).isEmpty();
  }

  @Test
  @DisplayName("Should check a mismatched target before returning a retained command result")
  void shouldCheckMismatchedTargetBeforeReturningRetainedCommandResult() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var commandId = UUID.randomUUID();
    var specification = jobSpecification();
    adapter.start(startCommand(commandId, target, specification));

    var result = adapter.start(startCommand(commandId, workerTarget(), specification));

    assertThat(result).isEqualTo(new StartJobResult.Rejected(StartJobRejection.TARGET_MISMATCH));
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
  }

  @Test
  @DisplayName("Should resolve and start a complete local job after accepting its exact target")
  void shouldResolveAndStartCompleteLocalJobAfterAcceptingExactTarget() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();

    var result = adapter.start(startCommand(target, specification));

    assertThat(result)
        .isInstanceOfSatisfying(
            StartJobResult.Accepted.class,
            accepted -> {
              assertThat(accepted.observation().jobRef()).isEqualTo(specification.jobRef());
              assertThat(accepted.observation().state()).isEqualTo(TranscodeJobState.RUNNING);
            });
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
    assertThat(processManager.getStarted()).containsExactly(specification.jobRef().jobId());
  }

  @Test
  @DisplayName("Should return the retained result for an identical start command delivery")
  void shouldReturnRetainedResultForIdenticalStartCommandDelivery() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    var command = startCommand(target, specification);

    var first = adapter.start(command);
    var duplicate = adapter.start(command);

    assertThat(duplicate).isEqualTo(first);
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
    assertThat(processManager.getStarted()).containsExactly(specification.jobRef().jobId());
  }

  @Test
  @DisplayName("Should retain an accepted command delivery after fencing its exact generation")
  void shouldRetainAcceptedCommandDeliveryAfterFencingItsExactGeneration() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    var command = startCommand(target, specification);
    var accepted = adapter.start(command);
    adapter.stop(stopCommand(target, specification.jobRef()));

    var retained = adapter.start(command);
    var newCommand = adapter.start(startCommand(target, specification));

    assertThat(retained).isEqualTo(accepted);
    assertThat(newCommand)
        .isEqualTo(new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION));
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
  }

  @Test
  @DisplayName("Should reuse the exact engine allocation for a new command identity")
  void shouldReuseExactEngineAllocationForNewCommandIdentity() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();

    var first = adapter.start(startCommand(target, specification));
    var retry = adapter.start(startCommand(target, specification));

    assertThat(retry).isEqualTo(first);
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
    assertThat(processManager.getStarted()).containsExactly(specification.jobRef().jobId());
  }

  @Test
  @DisplayName(
      "Should return the existing allocation under a new command when its source becomes unavailable")
  void shouldReturnExistingAllocationUnderNewCommandWhenSourceBecomesUnavailable() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    var accepted = adapter.start(startCommand(target, specification));
    catalog.failResolution(new MediaSourceUnavailableException());

    var repeated = adapter.start(startCommand(target, specification));

    assertThat(repeated).isEqualTo(accepted);
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
    assertThat(processManager.getStarted()).containsExactly(specification.jobRef().jobId());
  }

  @Test
  @DisplayName("Should return the current exact allocation state under a new command identity")
  void shouldReturnCurrentExactAllocationStateUnderNewCommandIdentity() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    var initial = adapter.start(startCommand(target, specification));
    processManager.stopJob(specification.jobRef());
    var failed =
        (InspectJobResult.Observed)
            adapter.inspect(new InspectJobQuery(target, specification.jobRef()));
    catalog.failResolution(new MediaSourceUnavailableException());

    var repeated = adapter.start(startCommand(target, specification));

    assertThat(failed.observation().state()).isEqualTo(TranscodeJobState.FAILED);
    assertThat(repeated)
        .isEqualTo(new StartJobResult.Accepted(failed.observation()))
        .isNotEqualTo(initial);
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
    assertThat(processManager.getStarted()).containsExactly(specification.jobRef().jobId());
  }

  @Test
  @DisplayName("Should join an in-flight exact start before resolving an unavailable source")
  void shouldJoinInFlightExactStartBeforeResolvingUnavailableSource() throws Exception {
    var target = workerTarget();
    var processManager = new BlockingStartFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> adapter.start(startCommand(target, specification)));
      assertThat(processManager.awaitStart()).isTrue();
      catalog.failResolution(new MediaSourceUnavailableException());
      var duplicate = new FutureTask<>(() -> adapter.start(startCommand(target, specification)));
      var duplicateThread = Thread.ofVirtual().unstarted(duplicate);
      duplicateThread.start();
      try {
        assertThat(awaitParkedInJoin(duplicateThread)).isTrue();
      } finally {
        processManager.releaseStart();
      }

      var accepted = first.get(2, TimeUnit.SECONDS);
      assertThat(accepted).isInstanceOf(StartJobResult.Accepted.class);
      assertThat(duplicate.get(2, TimeUnit.SECONDS)).isEqualTo(accepted);
    }
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
    assertThat(processManager.getStarted()).containsExactly(specification.jobRef().jobId());
  }

  @Test
  @DisplayName("Should reject an in-flight exact generation conflict without resolving again")
  void shouldRejectInFlightExactGenerationConflictWithoutResolvingAgain() throws Exception {
    var target = workerTarget();
    var processManager = new BlockingStartFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var accepted = jobSpecification();
    var conflicting =
        withSource(
            accepted,
            new MediaSourceRef(accepted.source().namespaceId(), "Movies/conflicting.mkv"));

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> adapter.start(startCommand(target, accepted)));
      assertThat(processManager.awaitStart()).isTrue();
      try {
        assertThat(adapter.start(startCommand(target, conflicting)))
            .isEqualTo(new StartJobResult.Rejected(StartJobRejection.JOB_CONFLICT));
      } finally {
        processManager.releaseStart();
      }
      assertThat(first.get(2, TimeUnit.SECONDS)).isInstanceOf(StartJobResult.Accepted.class);
    }
    assertThat(catalog.resolvedSources()).containsExactly(accepted.source());
  }

  @Test
  @DisplayName("Should share one exceptional in-flight exact start across command identities")
  void shouldShareOneExceptionalInFlightExactStartAcrossCommandIdentities() throws Exception {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var failure = new IllegalStateException("simulated catalog defect");
    var catalog = new BlockingMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    catalog.failResolution(failure);
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> adapter.start(startCommand(target, specification)));
      assertThat(catalog.awaitResolution()).isTrue();
      var duplicate = new FutureTask<>(() -> adapter.start(startCommand(target, specification)));
      var duplicateThread = Thread.ofVirtual().unstarted(duplicate);
      duplicateThread.start();
      try {
        assertThat(awaitParkedInJoin(duplicateThread)).isTrue();
      } finally {
        catalog.releaseResolution();
      }

      var firstDeliveryFailure = catchThrowable(() -> first.get(2, TimeUnit.SECONDS));
      var duplicateDeliveryFailure = catchThrowable(() -> duplicate.get(2, TimeUnit.SECONDS));
      assertThat(firstDeliveryFailure).isInstanceOf(ExecutionException.class);
      assertThat(duplicateDeliveryFailure).isInstanceOf(ExecutionException.class);
      assertThat(firstDeliveryFailure.getCause())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(failure.getMessage())
          .hasNoCause();
      assertThat(duplicateDeliveryFailure.getCause())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(failure.getMessage())
          .hasNoCause();
    }
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
    assertThat(processManager.getStarted()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeEngineException.Reason.class,
      names = {"STARTUP_FAILED", "CLEANUP_PENDING"})
  @DisplayName("Should retain the resolved source for an admitted exact failure")
  void shouldRetainResolvedSourceForAdmittedExactFailure(TranscodeEngineException.Reason reason) {
    var target = workerTarget();
    var processManager = new FailingStartFfmpegProcessManager(reason);
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    var first = adapter.start(startCommand(target, specification));
    catalog.failResolution(new MediaSourceUnavailableException());

    var repeated = adapter.start(startCommand(target, specification));

    assertThat(repeated).isEqualTo(first);
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
  }

  @Test
  @DisplayName("Should reject a start command identity reused for different job intent")
  void shouldRejectStartCommandIdentityReusedForDifferentJobIntent() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var commandId = UUID.randomUUID();
    var acceptedSpecification = jobSpecification();
    var accepted = startCommand(commandId, target, acceptedSpecification);
    var conflicting = startCommand(commandId, target, jobSpecification());
    adapter.start(accepted);

    var result = adapter.start(conflicting);

    assertThat(result).isEqualTo(new StartJobResult.Rejected(StartJobRejection.COMMAND_CONFLICT));
    assertThat(catalog.resolvedSources()).containsExactly(acceptedSpecification.source());
    assertThat(processManager.getStarted()).containsExactly(acceptedSpecification.jobRef().jobId());
  }

  @Test
  @DisplayName("Should reject conflicting exact-job content under a new command identity")
  void shouldRejectConflictingExactJobContentUnderNewCommandIdentity() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var accepted = jobSpecification();
    var conflicting =
        TranscodeJobSpec.builder()
            .sessionId(accepted.sessionId())
            .jobRef(accepted.jobRef())
            .source(accepted.source())
            .decision(accepted.decision())
            .execution(accepted.execution())
            .renditions(List.of(new RenditionSpec("default", 1280, 720, 3_500_000L)))
            .build();
    adapter.start(startCommand(target, accepted));

    var result = adapter.start(startCommand(target, conflicting));

    assertThat(result).isEqualTo(new StartJobResult.Rejected(StartJobRejection.JOB_CONFLICT));
    assertThat(catalog.resolvedSources()).containsExactly(accepted.source());
    assertThat(processManager.getStarted()).containsExactly(accepted.jobRef().jobId());
  }

  @Test
  @DisplayName("Should reject a lower generation after accepting a higher generation")
  void shouldRejectLowerGenerationAfterAcceptingHigherGeneration() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var lower = jobSpecification();
    var higher = withGeneration(lower, 2);
    adapter.start(startCommand(target, higher));

    var result = adapter.start(startCommand(target, lower));

    assertThat(result).isEqualTo(new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION));
    assertThat(processManager.getStarted()).containsExactly(lower.jobRef().jobId());
  }

  @Test
  @DisplayName("Should reject a lower generation after a higher generation fails startup")
  void shouldRejectLowerGenerationAfterHigherGenerationFailsStartup() {
    var target = workerTarget();
    var processManager =
        new FailingStartFfmpegProcessManager(TranscodeEngineException.Reason.STARTUP_FAILED);
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var lower = jobSpecification();
    var higher = withGeneration(lower, 2);
    adapter.start(startCommand(target, higher));

    var result = adapter.start(startCommand(target, lower));

    assertThat(result).isEqualTo(new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION));
  }

  @Test
  @DisplayName("Should invalidate a cached allocation after accepting a higher generation")
  void shouldInvalidateCachedAllocationAfterAcceptingHigherGeneration() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var lower = jobSpecification();
    var higher = withGeneration(lower, 2);
    adapter.start(startCommand(target, lower));
    adapter.start(startCommand(target, higher));

    var delayedLower = adapter.start(startCommand(target, lower));

    assertThat(delayedLower)
        .isEqualTo(new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION));
    assertThat(catalog.resolvedSources()).containsExactly(lower.source(), higher.source());
  }

  @Test
  @DisplayName("Should invalidate a cached allocation after a higher generation fails startup")
  void shouldInvalidateCachedAllocationAfterHigherGenerationFailsStartup() {
    var target = workerTarget();
    var processManager = new FailingReplacementFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var lower = jobSpecification();
    var higher = withGeneration(lower, 2);
    adapter.start(startCommand(target, lower));
    processManager.failFutureStarts();

    var failedHigher = adapter.start(startCommand(target, higher));
    var delayedLower = adapter.start(startCommand(target, lower));

    assertThat(failedHigher)
        .isEqualTo(new StartJobResult.Rejected(StartJobRejection.STARTUP_FAILED));
    assertThat(delayedLower)
        .isEqualTo(new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION));
    assertThat(catalog.resolvedSources()).containsExactly(lower.source(), higher.source());
  }

  @ParameterizedTest(name = "{0} maps to {1}")
  @MethodSource("startRejections")
  @DisplayName("Should map each engine start rejection without losing its meaning")
  void shouldMapEachEngineStartRejectionWithoutLosingItsMeaning(
      TranscodeEngineException.Reason engineReason, StartJobRejection rejection) {
    var target = workerTarget();
    var processManager = new FailingStartFfmpegProcessManager(engineReason);
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));

    var result = adapter.start(startCommand(target, jobSpecification()));

    assertThat(result).isEqualTo(new StartJobResult.Rejected(rejection));
  }

  @Test
  @DisplayName("Should preserve cleanup uncertainty when local startup compensation is incomplete")
  void shouldPreserveCleanupUncertaintyWhenLocalStartupCompensationIsIncomplete() {
    var target = workerTarget();
    var processManager =
        new FailingStartFfmpegProcessManager(TranscodeEngineException.Reason.CLEANUP_PENDING);
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();

    var result = adapter.start(startCommand(target, specification));

    assertThat(result).isEqualTo(new StartJobResult.CleanupPending(specification.jobRef()));
  }

  @Test
  @DisplayName("Should reject a mismatched stop target before changing the exact job")
  void shouldRejectMismatchedStopTargetBeforeChangingExactJob() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    adapter.start(startCommand(target, specification));
    var command = stopCommand(workerTarget(), specification.jobRef());

    var result = adapter.stop(command);

    assertThat(result).isEqualTo(new StopJobResult.Rejected(StopJobRejection.TARGET_MISMATCH));
    assertThat(processManager.getStopped()).isEmpty();
  }

  @Test
  @DisplayName("Should stop the exact job and release its terminal engine observation")
  void shouldStopExactJobAndReleaseItsTerminalEngineObservation() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    adapter.start(startCommand(target, specification));

    var stopped = adapter.stop(stopCommand(target, specification.jobRef()));
    var absent = adapter.stop(stopCommand(target, specification.jobRef()));

    assertThat(stopped).isEqualTo(new StopJobResult.Stopped(specification.jobRef()));
    assertThat(absent).isEqualTo(new StopJobResult.AlreadyAbsent(specification.jobRef()));
    assertThat(processManager.getStopped()).containsExactly(specification.jobRef().jobId());
  }

  @Test
  @DisplayName("Should not start an exact generation after its stop was already accepted")
  void shouldNotStartExactGenerationAfterItsStopWasAlreadyAccepted() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    var stopped = adapter.stop(stopCommand(target, specification.jobRef()));

    var delayedStart = adapter.start(startCommand(target, specification));
    var higher = withGeneration(specification, 2);
    var higherStart = adapter.start(startCommand(target, higher));

    assertThat(stopped).isEqualTo(new StopJobResult.AlreadyAbsent(specification.jobRef()));
    assertThat(delayedStart)
        .isEqualTo(new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION));
    assertThat(higherStart).isInstanceOf(StartJobResult.Accepted.class);
    assertThat(catalog.resolvedSources()).containsExactly(higher.source());
    assertThat(processManager.getStarted()).containsExactly(specification.jobRef().jobId());
  }

  @Test
  @DisplayName("Should reject a lower generation after an absent higher stop before source lookup")
  void shouldRejectLowerGenerationAfterAbsentHigherStopBeforeSourceLookup() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("missing.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var lower = jobSpecification();
    var stopped = withGeneration(lower, 2).jobRef();
    assertThat(adapter.stop(stopCommand(target, stopped)))
        .isEqualTo(new StopJobResult.AlreadyAbsent(stopped));
    catalog.failResolution(new MediaSourceUnavailableException());

    var result = adapter.start(startCommand(target, lower));

    assertThat(result).isEqualTo(new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION));
    assertThat(catalog.resolvedSources()).isEmpty();
    assertThat(processManager.getStarted()).isEmpty();
  }

  @Test
  @DisplayName("Should linearize concurrent reordered start and stop for one exact generation")
  void shouldLinearizeConcurrentReorderedStartAndStopForOneExactGeneration() throws Exception {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    var barrier = new CyclicBarrier(2);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start =
          executor.submit(
              () -> {
                barrier.await(1, TimeUnit.SECONDS);
                return adapter.start(startCommand(target, specification));
              });
      var stop =
          executor.submit(
              () -> {
                barrier.await(1, TimeUnit.SECONDS);
                return adapter.stop(stopCommand(target, specification.jobRef()));
              });
      var startResult = start.get(2, TimeUnit.SECONDS);
      var stopResult = stop.get(2, TimeUnit.SECONDS);

      if (startResult instanceof StartJobResult.Accepted) {
        assertThat(stopResult).isEqualTo(new StopJobResult.Stopped(specification.jobRef()));
      } else {
        assertThat(startResult)
            .isEqualTo(new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION));
        assertThat(stopResult)
            .isIn(
                new StopJobResult.AlreadyAbsent(specification.jobRef()),
                new StopJobResult.Stopped(specification.jobRef()));
      }
    }
    assertThat(adapter.inspect(new InspectJobQuery(target, specification.jobRef())))
        .isInstanceOfSatisfying(
            InspectJobResult.Observed.class,
            observed ->
                assertThat(observed.observation().state()).isEqualTo(TranscodeJobState.ABSENT));
    assertThat(adapter.start(startCommand(target, withGeneration(specification, 2))))
        .isInstanceOf(StartJobResult.Accepted.class);
  }

  @Test
  @DisplayName("Should let an exact stop promptly cancel a blocked startup")
  void shouldLetExactStopPromptlyCancelBlockedStartup() throws Exception {
    var target = workerTarget();
    var processManager = new BlockingStartFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var starting = executor.submit(() -> adapter.start(startCommand(target, specification)));
      assertThat(processManager.awaitStart()).isTrue();
      var stopping =
          executor.submit(() -> adapter.stop(stopCommand(target, specification.jobRef())));
      try {
        assertThat(processManager.awaitStop()).isTrue();
      } finally {
        processManager.releaseStart();
      }

      assertThat(starting.get(2, TimeUnit.SECONDS))
          .isEqualTo(new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION));
      assertThat(stopping.get(2, TimeUnit.SECONDS))
          .isEqualTo(new StopJobResult.Stopped(specification.jobRef()));
    }
    assertThat(adapter.inspect(new InspectJobQuery(target, specification.jobRef())))
        .isInstanceOfSatisfying(
            InspectJobResult.Observed.class,
            observed ->
                assertThat(observed.observation().state()).isEqualTo(TranscodeJobState.ABSENT));
  }

  @Test
  @DisplayName("Should let an exact stop fence a start while source resolution is blocked")
  void shouldLetExactStopFenceStartWhileSourceResolutionIsBlocked() throws Exception {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new BlockingMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var starting = executor.submit(() -> adapter.start(startCommand(target, specification)));
      assertThat(catalog.awaitResolution()).isTrue();
      var stopping =
          executor.submit(() -> adapter.stop(stopCommand(target, specification.jobRef())));
      try {
        assertThat(stopping.get(1, TimeUnit.SECONDS))
            .isEqualTo(new StopJobResult.AlreadyAbsent(specification.jobRef()));
      } finally {
        catalog.releaseResolution();
      }

      assertThat(starting.get(2, TimeUnit.SECONDS))
          .isEqualTo(new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION));
    }
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
    assertThat(processManager.getStarted()).isEmpty();
  }

  @Test
  @DisplayName("Should expose an exact stop fence while observation release is blocked")
  void shouldExposeExactStopFenceWhileObservationReleaseIsBlocked() throws Exception {
    var target = workerTarget();
    var processManager = new BlockingReleaseFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    adapter.start(startCommand(target, specification));

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var stopping =
          executor.submit(() -> adapter.stop(stopCommand(target, specification.jobRef())));
      assertThat(processManager.awaitRelease()).isTrue();
      var starting = executor.submit(() -> adapter.start(startCommand(target, specification)));
      try {
        assertThat(starting.get(1, TimeUnit.SECONDS))
            .isEqualTo(new StartJobResult.Rejected(StartJobRejection.STALE_GENERATION));
      } finally {
        processManager.releaseObservation();
      }

      assertThat(stopping.get(2, TimeUnit.SECONDS))
          .isEqualTo(new StopJobResult.Stopped(specification.jobRef()));
    }
  }

  @Test
  @DisplayName("Should return the retained result for an identical stop command delivery")
  void shouldReturnRetainedResultForIdenticalStopCommandDelivery() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    adapter.start(startCommand(target, specification));
    var command = stopCommand(target, specification.jobRef());

    var first = adapter.stop(command);
    var duplicate = adapter.stop(command);

    assertThat(first).isEqualTo(new StopJobResult.Stopped(specification.jobRef()));
    assertThat(duplicate).isEqualTo(first);
  }

  @Test
  @DisplayName("Should reject command identity reuse across the start and stop namespace")
  void shouldRejectCommandIdentityReuseAcrossStartAndStopNamespace() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var commandId = UUID.randomUUID();
    var specification = jobSpecification();
    adapter.start(startCommand(commandId, target, specification));

    var result = adapter.stop(stopCommand(commandId, target, specification.jobRef()));

    assertThat(result).isEqualTo(new StopJobResult.Rejected(StopJobRejection.COMMAND_CONFLICT));
    assertThat(processManager.getStopped()).isEmpty();
  }

  @Test
  @DisplayName("Should reject command identity reuse across the stop and start namespace")
  void shouldRejectCommandIdentityReuseAcrossStopAndStartNamespace() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var commandId = UUID.randomUUID();
    var specification = jobSpecification();
    adapter.stop(stopCommand(commandId, target, specification.jobRef()));

    var result = adapter.start(startCommand(commandId, target, specification));

    assertThat(result).isEqualTo(new StartJobResult.Rejected(StartJobRejection.COMMAND_CONFLICT));
    assertThat(catalog.resolvedSources()).isEmpty();
    assertThat(processManager.getStarted()).isEmpty();
  }

  @Test
  @DisplayName("Should retain one result when duplicate starts arrive concurrently")
  void shouldRetainOneResultWhenDuplicateStartsArriveConcurrently() throws Exception {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    var command = startCommand(target, specification);
    var deliveryCount = 12;
    var barrier = new CyclicBarrier(deliveryCount);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var deliveries =
          java.util.stream.IntStream.range(0, deliveryCount)
              .mapToObj(
                  _ ->
                      executor.submit(
                          () -> {
                            barrier.await(1, TimeUnit.SECONDS);
                            return adapter.start(command);
                          }))
              .toList();
      var results = new ArrayList<StartJobResult>();
      for (var delivery : deliveries) {
        results.add(delivery.get(2, TimeUnit.SECONDS));
      }

      assertThat(results).allMatch(results.getFirst()::equals);
    }
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
    assertThat(processManager.getStarted()).containsExactly(specification.jobRef().jobId());
  }

  @Test
  @DisplayName("Should reject an unavailable portable source without starting a process")
  void shouldRejectUnavailablePortableSourceWithoutStartingProcess() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("missing.mkv"));
    catalog.failResolution(new MediaSourceUnavailableException());
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();

    var result = adapter.start(startCommand(target, specification));

    assertThat(result).isEqualTo(new StartJobResult.Rejected(StartJobRejection.SOURCE_UNAVAILABLE));
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
    assertThat(processManager.getStarted()).isEmpty();
  }

  @Test
  @DisplayName("Should retain an unexpected start failure for identical command retries")
  void shouldRetainUnexpectedStartFailureForIdenticalCommandRetries() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    catalog.failResolution(new IllegalStateException("simulated catalog defect"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    var command = startCommand(target, specification);

    assertThatThrownBy(() -> adapter.start(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated catalog defect");
    assertThatThrownBy(() -> adapter.start(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated catalog defect");
    assertThat(catalog.resolvedSources()).containsExactly(specification.source());
    assertThat(processManager.getStarted()).isEmpty();
  }

  @Test
  @DisplayName("Should reject inspection for a mismatched worker incarnation")
  void shouldRejectInspectionForMismatchedWorkerIncarnation() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var query = new InspectJobQuery(workerTarget(), new TranscodeJobRef(UUID.randomUUID(), 1));

    var result = adapter.inspect(query);

    assertThat(result)
        .isEqualTo(new InspectJobResult.Rejected(InspectJobRejection.TARGET_MISMATCH));
  }

  @Test
  @DisplayName("Should inspect the exact local job through the typed observation result")
  void shouldInspectExactLocalJobThroughTypedObservationResult() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    var started = (StartJobResult.Accepted) adapter.start(startCommand(target, specification));

    var result = adapter.inspect(new InspectJobQuery(target, specification.jobRef()));

    assertThat(result).isEqualTo(new InspectJobResult.Observed(started.observation()));
  }

  @Test
  @DisplayName("Should report an exact missing local job as an absent observation")
  void shouldReportExactMissingLocalJobAsAbsentObservation() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 1);

    var result = adapter.inspect(new InspectJobQuery(target, jobRef));

    assertThat(result)
        .isInstanceOfSatisfying(
            InspectJobResult.Observed.class,
            observed -> {
              assertThat(observed.observation().jobRef()).isEqualTo(jobRef);
              assertThat(observed.observation().state()).isEqualTo(TranscodeJobState.ABSENT);
            });
  }

  @Test
  @DisplayName("Should keep inspection cleanup uncertainty inside the typed worker result")
  void shouldKeepInspectionCleanupUncertaintyInsideTypedWorkerResult() {
    var target = workerTarget();
    var processManager = new CleanupPendingInspectFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    adapter.start(startCommand(target, specification));
    processManager.failAndRefuseCleanup(specification.jobRef());

    var result = adapter.inspect(new InspectJobQuery(target, specification.jobRef()));

    assertThat(result).isEqualTo(new InspectJobResult.CleanupPending(specification.jobRef()));
  }

  @Test
  @DisplayName("Should preserve cleanup uncertainty when stopping the engine is incomplete")
  void shouldPreserveCleanupUncertaintyWhenStoppingEngineIsIncomplete() {
    var target = workerTarget();
    var processManager = new CleanupPendingStopFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    adapter.start(startCommand(target, specification));
    var command = stopCommand(target, specification.jobRef());

    var result = adapter.stop(command);
    var duplicate = adapter.stop(command);

    assertThat(result).isEqualTo(new StopJobResult.CleanupPending(specification.jobRef()));
    assertThat(duplicate).isEqualTo(result);
  }

  @Test
  @DisplayName("Should report cleanup pending when terminal observation release is refused")
  void shouldReportCleanupPendingWhenTerminalObservationReleaseIsRefused() {
    var target = workerTarget();
    var processManager = new ReadyFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    adapter.start(startCommand(target, specification));
    processManager.preventObservationRelease();

    var result = adapter.stop(stopCommand(target, specification.jobRef()));

    assertThat(result).isEqualTo(new StopJobResult.CleanupPending(specification.jobRef()));
  }

  @Test
  @DisplayName("Should retain an unexpected stop failure for identical command retries")
  void shouldRetainUnexpectedStopFailureForIdenticalCommandRetries() {
    var target = workerTarget();
    var processManager = new FailingReleaseFfmpegProcessManager();
    var catalog = new FakeMediaSourceCatalog(tempDir.resolve("movie.mkv"));
    var adapter = new LocalTranscodeWorkerAdapter(target, catalog, engine(processManager));
    var specification = jobSpecification();
    adapter.start(startCommand(target, specification));
    var command = stopCommand(target, specification.jobRef());

    assertThatThrownBy(() -> adapter.stop(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated observation release defect");
    assertThatThrownBy(() -> adapter.stop(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated observation release defect");
  }

  private static Stream<Arguments> startRejections() {
    return Stream.of(
        Arguments.of(
            TranscodeEngineException.Reason.STALE_GENERATION, StartJobRejection.STALE_GENERATION),
        Arguments.of(TranscodeEngineException.Reason.JOB_CONFLICT, StartJobRejection.JOB_CONFLICT),
        Arguments.of(
            TranscodeEngineException.Reason.SESSION_CONFLICT, StartJobRejection.JOB_CONFLICT),
        Arguments.of(
            TranscodeEngineException.Reason.INVALID_SPECIFICATION,
            StartJobRejection.INVALID_SPECIFICATION),
        Arguments.of(
            TranscodeEngineException.Reason.STARTUP_FAILED, StartJobRejection.STARTUP_FAILED),
        Arguments.of(
            TranscodeEngineException.Reason.SHUTTING_DOWN, StartJobRejection.SHUTTING_DOWN));
  }

  private LocalTranscodeEngine engine(FakeFfmpegProcessManager processManager) {
    var capabilities =
        new TranscodeCapabilityService(
            "ffmpeg",
            _ -> {
              throw new UnsupportedOperationException();
            });
    return LocalTranscodeEngine.builder()
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg"))
        .processManager(processManager)
        .segmentStorage(new LocalSegmentStorage(tempDir.resolve("segments")))
        .capabilityService(capabilities)
        .build();
  }

  private static StartJobCommand startCommand(WorkerTarget target, TranscodeJobSpec specification) {
    return startCommand(UUID.randomUUID(), target, specification);
  }

  private static StartJobCommand startCommand(
      UUID commandId, WorkerTarget target, TranscodeJobSpec specification) {
    return StartJobCommand.builder()
        .commandId(commandId)
        .target(target)
        .specification(specification)
        .build();
  }

  private static WorkerTarget workerTarget() {
    return new WorkerTarget(UUID.randomUUID(), UUID.randomUUID());
  }

  private static StopJobCommand stopCommand(WorkerTarget target, TranscodeJobRef jobRef) {
    return stopCommand(UUID.randomUUID(), target, jobRef);
  }

  private static StopJobCommand stopCommand(
      UUID commandId, WorkerTarget target, TranscodeJobRef jobRef) {
    return StopJobCommand.builder().commandId(commandId).target(target).jobRef(jobRef).build();
  }

  private static TranscodeJobSpec jobSpecification() {
    return TranscodeJobSpec.builder()
        .sessionId(UUID.randomUUID())
        .jobRef(new TranscodeJobRef(UUID.randomUUID(), 1))
        .source(new MediaSourceRef(UUID.randomUUID(), "Movies/movie.mkv"))
        .decision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.REMUX)
                .videoCodecFamily("h264")
                .audioDecision(AudioDecision.copy("aac", 2, 128_000L))
                .subtitleDecision(SubtitleDecision.exclude())
                .containerFormat(ContainerFormat.MPEGTS)
                .build())
        .execution(
            TranscodeExecutionParameters.builder()
                .seekPosition(0)
                .segmentDuration(6)
                .framerate(23.976)
                .startNumber(0)
                .startupTimeout(Duration.ofMillis(100))
                .build())
        .renditions(List.of(new RenditionSpec("default", 1280, 720, 3_000_000L)))
        .build();
  }

  private static TranscodeJobSpec withGeneration(TranscodeJobSpec specification, long generation) {
    return TranscodeJobSpec.builder()
        .sessionId(specification.sessionId())
        .jobRef(new TranscodeJobRef(specification.jobRef().jobId(), generation))
        .source(specification.source())
        .decision(specification.decision())
        .execution(specification.execution())
        .renditions(specification.renditions())
        .build();
  }

  private static TranscodeJobSpec withSource(
      TranscodeJobSpec specification, MediaSourceRef source) {
    return TranscodeJobSpec.builder()
        .sessionId(specification.sessionId())
        .jobRef(specification.jobRef())
        .source(source)
        .decision(specification.decision())
        .execution(specification.execution())
        .renditions(specification.renditions())
        .build();
  }

  private static boolean awaitParkedInJoin(Thread thread) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (System.nanoTime() < deadline) {
      if (isParkedInJoin(thread)) {
        return true;
      }
      Thread.onSpinWait();
    }
    return false;
  }

  private static boolean isParkedInJoin(Thread thread) {
    if (thread.getState() != Thread.State.WAITING) {
      return false;
    }
    return Stream.of(thread.getStackTrace())
        .anyMatch(
            frame ->
                frame.getClassName().equals(CompletableFuture.class.getName())
                    && frame.getMethodName().equals("join"));
  }

  private static class FakeMediaSourceCatalog implements MediaSourceCatalog {

    private final Path resolvedPath;
    private final List<MediaSourceRef> resolvedSources = new CopyOnWriteArrayList<>();
    private RuntimeException resolutionFailure;

    private FakeMediaSourceCatalog(Path resolvedPath) {
      this.resolvedPath = resolvedPath;
    }

    @Override
    public MediaSourceRef referenceFor(UUID mediaFileId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Path resolve(MediaSourceRef source) {
      resolvedSources.add(source);
      if (resolutionFailure != null) {
        throw resolutionFailure;
      }
      return resolvedPath;
    }

    private void failResolution(RuntimeException failure) {
      resolutionFailure = failure;
    }

    List<MediaSourceRef> resolvedSources() {
      return List.copyOf(resolvedSources);
    }
  }

  private static final class BlockingMediaSourceCatalog extends FakeMediaSourceCatalog {

    private final CountDownLatch resolutionEntered = new CountDownLatch(1);
    private final CountDownLatch releaseResolution = new CountDownLatch(1);
    private RuntimeException failureAfterRelease;

    private BlockingMediaSourceCatalog(Path resolvedPath) {
      super(resolvedPath);
    }

    @Override
    public Path resolve(MediaSourceRef source) {
      resolutionEntered.countDown();
      await(releaseResolution);
      var resolved = super.resolve(source);
      if (failureAfterRelease != null) {
        throw failureAfterRelease;
      }
      return resolved;
    }

    private void failResolution(RuntimeException failure) {
      failureAfterRelease = failure;
    }

    private boolean awaitResolution() throws InterruptedException {
      return resolutionEntered.await(1, TimeUnit.SECONDS);
    }

    private void releaseResolution() {
      releaseResolution.countDown();
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(2, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Timed out waiting to release source resolution");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Source resolution was interrupted", exception);
      }
    }
  }

  private static class ReadyFfmpegProcessManager extends FakeFfmpegProcessManager {

    @Override
    public Process startProcess(FfmpegProcessKey key, List<String> command, Path workingDir) {
      var process = super.startProcess(key, command, workingDir);
      try {
        Files.writeString(workingDir.resolve("stream.m3u8"), "#EXTM3U\n");
        Files.writeString(workingDir.resolve("segment0.ts"), "segment-zero");
      } catch (IOException exception) {
        throw new UncheckedIOException(exception);
      }
      return process;
    }
  }

  private static final class CleanupPendingStopFfmpegProcessManager
      extends ReadyFfmpegProcessManager {

    @Override
    public void stopJob(TranscodeJobRef jobRef) {
      throw new TranscodeEngineException(
          TranscodeEngineException.Reason.CLEANUP_PENDING, "simulated cleanup uncertainty");
    }
  }

  private static final class CleanupPendingInspectFfmpegProcessManager
      extends ReadyFfmpegProcessManager {

    private boolean refuseCleanup;

    private void failAndRefuseCleanup(TranscodeJobRef jobRef) {
      super.stopJob(jobRef);
      refuseCleanup = true;
    }

    @Override
    public void stopJob(TranscodeJobRef jobRef) {
      if (refuseCleanup) {
        throw new IllegalStateException("simulated inspection cleanup uncertainty");
      }
      super.stopJob(jobRef);
    }
  }

  private static final class FailingReleaseFfmpegProcessManager extends ReadyFfmpegProcessManager {

    @Override
    public boolean releaseJobObservation(TranscodeJobRef jobRef) {
      throw new IllegalStateException("simulated observation release defect");
    }
  }

  private static final class BlockingReleaseFfmpegProcessManager extends ReadyFfmpegProcessManager {

    private final CountDownLatch releaseEntered = new CountDownLatch(1);
    private final CountDownLatch allowRelease = new CountDownLatch(1);

    @Override
    public boolean releaseJobObservation(TranscodeJobRef jobRef) {
      releaseEntered.countDown();
      await(allowRelease);
      return super.releaseJobObservation(jobRef);
    }

    private boolean awaitRelease() throws InterruptedException {
      return releaseEntered.await(1, TimeUnit.SECONDS);
    }

    private void releaseObservation() {
      allowRelease.countDown();
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(2, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Timed out waiting to release observation cleanup");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Observation cleanup was interrupted", exception);
      }
    }
  }

  private static final class BlockingStartFfmpegProcessManager extends ReadyFfmpegProcessManager {

    private final CountDownLatch startEntered = new CountDownLatch(1);
    private final CountDownLatch stopEntered = new CountDownLatch(1);
    private final CountDownLatch releaseStart = new CountDownLatch(1);

    @Override
    public Process startProcess(FfmpegProcessKey key, List<String> command, Path workingDir) {
      var process = super.startProcess(key, command, workingDir);
      startEntered.countDown();
      await(releaseStart);
      return process;
    }

    @Override
    public void stopJob(TranscodeJobRef jobRef) {
      stopEntered.countDown();
      super.stopJob(jobRef);
    }

    private boolean awaitStart() throws InterruptedException {
      return startEntered.await(1, TimeUnit.SECONDS);
    }

    private boolean awaitStop() throws InterruptedException {
      return stopEntered.await(1, TimeUnit.SECONDS);
    }

    private void releaseStart() {
      releaseStart.countDown();
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(2, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Timed out waiting to release blocked startup");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Blocked startup was interrupted", exception);
      }
    }
  }

  private static final class FailingReplacementFfmpegProcessManager
      extends ReadyFfmpegProcessManager {

    private boolean failFutureStarts;

    private void failFutureStarts() {
      failFutureStarts = true;
    }

    @Override
    public Process startProcess(FfmpegProcessKey key, List<String> command, Path workingDir) {
      if (failFutureStarts) {
        throw new TranscodeEngineException(
            TranscodeEngineException.Reason.STARTUP_FAILED, "simulated replacement failure");
      }
      return super.startProcess(key, command, workingDir);
    }
  }

  private static final class FailingStartFfmpegProcessManager extends FakeFfmpegProcessManager {

    private final TranscodeEngineException.Reason reason;

    private FailingStartFfmpegProcessManager(TranscodeEngineException.Reason reason) {
      this.reason = reason;
    }

    @Override
    public Process startProcess(FfmpegProcessKey key, List<String> command, Path workingDir) {
      throw new TranscodeEngineException(reason, "simulated engine rejection");
    }
  }
}
