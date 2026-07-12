package com.streamarr.transcode.engine.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.error.TranscodeException;
import com.streamarr.transcode.engine.fakes.FakeFfmpegProcessManager;
import com.streamarr.transcode.engine.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessKey;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessState;
import com.streamarr.transcode.engine.ffmpeg.TranscodeCapabilityService;
import com.streamarr.transcode.engine.model.AudioDecision;
import com.streamarr.transcode.engine.model.ContainerFormat;
import com.streamarr.transcode.engine.model.MediaSourceRef;
import com.streamarr.transcode.engine.model.RenditionObservation;
import com.streamarr.transcode.engine.model.RenditionSpec;
import com.streamarr.transcode.engine.model.RenditionState;
import com.streamarr.transcode.engine.model.SubtitleDecision;
import com.streamarr.transcode.engine.model.SubtitleMode;
import com.streamarr.transcode.engine.model.TranscodeDecision;
import com.streamarr.transcode.engine.model.TranscodeExecutionParameters;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import com.streamarr.transcode.engine.model.TranscodeJobSpec;
import com.streamarr.transcode.engine.model.TranscodeJobState;
import com.streamarr.transcode.engine.model.TranscodeMode;
import com.streamarr.transcode.engine.segment.LocalSegmentStorage;
import com.streamarr.transcode.engine.segment.SegmentGeneration;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Local Transcode Engine Tests")
class LocalTranscodeEngineTest {

  @TempDir Path tempDir;

  private FakeFfmpegProcessManager processManager;
  private LocalSegmentStorage segmentStorage;
  private LocalTranscodeEngine engine;

  @BeforeEach
  void setUp() {
    processManager = new FakeFfmpegProcessManager();
    segmentStorage = new LocalSegmentStorage(tempDir.resolve("segments"));
    var capabilities =
        new TranscodeCapabilityService(
            "ffmpeg",
            _ -> {
              throw new UnsupportedOperationException();
            });
    engine =
        LocalTranscodeEngine.builder()
            .commandBuilder(new FfmpegCommandBuilder("ffmpeg"))
            .processManager(processManager)
            .segmentStorage(segmentStorage)
            .capabilityService(capabilities)
            .build();
  }

  @Test
  @DisplayName("Should publish a complete single-rendition job after startup artifacts are ready")
  void shouldPublishCompleteSingleRenditionJobAfterStartupArtifactsAreReady() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> {
          Files.writeString(outputDirectory.resolve("stream.m3u8"), "#EXTM3U\n");
          Files.writeString(outputDirectory.resolve("segment0.ts"), "segment-zero");
        });

    var observation = engine.start(specification, source);

    assertThat(observation.jobRef()).isEqualTo(specification.jobRef());
    assertThat(observation.state()).isEqualTo(TranscodeJobState.RUNNING);
    assertThat(observation.renditions())
        .extracting(RenditionObservation::state)
        .containsExactly(RenditionState.RUNNING);
    assertThat(processManager.startedKeys())
        .containsExactly(new FfmpegProcessKey(specification.jobRef(), "default"));
    assertThat(segmentStorage.readSegment(specification.sessionId(), "segment0.ts"))
        .isEqualTo("segment-zero".getBytes());
  }

  @Test
  @DisplayName("Should expose every rendition atomically when the complete ladder is ready")
  void shouldExposeEveryRenditionAtomicallyWhenCompleteLadderIsReady() throws IOException {
    var lowRendition = rendition("480p", 854, 480, 1_500_000L);
    var highRendition = rendition("1080p", 1920, 1080, 5_000_000L);
    var specification = jobSpecification(List.of(lowRendition, highRendition));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (key, outputDirectory) -> {
          writeStartupArtifacts(outputDirectory, "segment-" + key.renditionLabel(), ".ts");
          if (key.renditionLabel().equals("480p")) {
            assertThat(segmentStorage.segmentExists(specification.sessionId(), "480p/segment0.ts"))
                .isFalse();
          }
        });

    var observation = engine.start(specification, source);

    assertThat(observation.renditions())
        .extracting(RenditionObservation::state)
        .containsExactly(RenditionState.RUNNING, RenditionState.RUNNING);
    assertThat(segmentStorage.readSegment(specification.sessionId(), "480p/segment0.ts"))
        .isEqualTo("segment-480p".getBytes());
    assertThat(segmentStorage.readSegment(specification.sessionId(), "1080p/segment0.ts"))
        .isEqualTo("segment-1080p".getBytes());
  }

  @Test
  @DisplayName("Should publish a single named rendition under its variant directory")
  void shouldPublishSingleNamedRenditionUnderVariantDirectory() throws IOException {
    var specification = jobSpecification(List.of(rendition("720p", 1280, 720, 2_500_000L)));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "named-segment", ".ts"));

    engine.start(specification, source);

    assertThat(segmentStorage.readSegment(specification.sessionId(), "720p/segment0.ts"))
        .isEqualTo("named-segment".getBytes());
    assertThat(segmentStorage.segmentExists(specification.sessionId(), "segment0.ts")).isFalse();
  }

  @Test
  @DisplayName("Should fail the complete job immediately when a rendition process exits nonzero")
  void shouldFailCompleteJobImmediatelyWhenRenditionProcessExitsNonzero() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()), Duration.ofMillis(100));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart((key, _) -> processManager.failProcess(key, 23));

    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOf(TranscodeException.class)
        .hasMessageContaining("failed");

    assertThat(processManager.isRunning(specification.jobRef())).isFalse();
    assertThat(segmentStorage.segmentExists(specification.sessionId(), "segment0.ts")).isFalse();
  }

  @Test
  @DisplayName("Should reject a rendition that fails immediately before publication")
  void shouldRejectRenditionThatFailsImmediatelyBeforePublication() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var observations = new AtomicInteger();
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    processManager.onObserve(
        key -> {
          if (observations.incrementAndGet() == 2) {
            processManager.failProcess(key, 23);
          }
        });

    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.STARTUP_FAILED));
    assertThat(segmentStorage.segmentExists(specification.sessionId(), "segment0.ts")).isFalse();
  }

  @Test
  @DisplayName("Should stop the complete job and discard staging when startup times out")
  void shouldStopCompleteJobAndDiscardStagingWhenStartupTimesOut() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()), Duration.ofMillis(50));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var generationRoot = new AtomicReference<Path>();
    processManager.onStart((_, outputDirectory) -> generationRoot.set(outputDirectory));

    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOf(TranscodeException.class)
        .hasMessageContaining("timed out");

    assertThat(processManager.isRunning(specification.jobRef())).isFalse();
    assertThat(generationRoot.get()).doesNotExist();
  }

  @Test
  @DisplayName("Should compensate an already-started rendition when a later spawn fails")
  void shouldCompensateAlreadyStartedRenditionWhenLaterSpawnFails() throws IOException {
    var low = rendition("480p", 854, 480, 1_500_000L);
    var high = rendition("1080p", 1920, 1080, 5_000_000L);
    var specification = jobSpecification(List.of(low, high));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var generationRoot = new AtomicReference<Path>();
    processManager.onStart(
        (key, outputDirectory) -> {
          if (key.renditionLabel().equals("1080p")) {
            throw new TranscodeException("spawn failed");
          }
          generationRoot.set(outputDirectory.getParent());
        });

    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOf(TranscodeException.class)
        .hasMessage("spawn failed");

    var firstKey = new FfmpegProcessKey(specification.jobRef(), "480p");
    assertThat(processManager.observe(firstKey).state()).isEqualTo(FfmpegProcessState.STOPPED);
    assertThat(generationRoot.get()).doesNotExist();
  }

  @Test
  @DisplayName("Should compensate the complete job and preserve interruption during startup")
  void shouldCompensateCompleteJobAndPreserveInterruptionDuringStartup() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var generationRoot = new AtomicReference<Path>();
    processManager.onStart((_, outputDirectory) -> generationRoot.set(outputDirectory));

    try {
      Thread.currentThread().interrupt();

      assertThatThrownBy(() -> engine.start(specification, source))
          .isInstanceOf(TranscodeException.class)
          .hasMessageContaining("interrupted");

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(processManager.isRunning(specification.jobRef())).isFalse();
      assertThat(generationRoot.get()).doesNotExist();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should inspect a running complete job by its exact generation")
  void shouldInspectRunningCompleteJobByExactGeneration() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    var started = engine.start(specification, source);

    var inspected = engine.inspect(specification.jobRef());

    assertThat(inspected).isEqualTo(started);
  }

  @Test
  @DisplayName("Should join an in-flight duplicate start for the same exact job")
  void shouldJoinInFlightDuplicateStartForSameExactJob() throws Exception {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var processStarted = new CountDownLatch(1);
    var allowReadiness = new CountDownLatch(1);
    processManager.onStart(
        (_, outputDirectory) -> {
          processStarted.countDown();
          await(allowReadiness);
          writeStartupArtifacts(outputDirectory, "segment", ".ts");
        });

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var original = executor.submit(() -> engine.start(specification, source));
      assertThat(processStarted.await(1, TimeUnit.SECONDS)).isTrue();
      var duplicate = executor.submit(() -> engine.start(specification, source));
      allowReadiness.countDown();

      assertThat(duplicate.get(2, TimeUnit.SECONDS)).isEqualTo(original.get(2, TimeUnit.SECONDS));
    }
    assertThat(processManager.startedKeys())
        .containsExactly(new FfmpegProcessKey(specification.jobRef(), "default"));
  }

  @Test
  @DisplayName("Should check segment readiness without loading segment contents")
  void shouldCheckSegmentReadinessWithoutLoadingSegmentContents() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> {
          Files.writeString(outputDirectory.resolve("stream.m3u8"), "#EXTM3U");
          try (var segment =
              Files.newByteChannel(
                  outputDirectory.resolve("segment0.ts"),
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.WRITE)) {
            segment.position((long) Integer.MAX_VALUE);
            segment.write(ByteBuffer.wrap(new byte[] {1}));
          }
        });

    var observation = engine.start(specification, source);

    assertThat(observation.state()).isEqualTo(TranscodeJobState.RUNNING);
  }

  @Test
  @DisplayName("Should reject conflicting content for an existing exact job")
  void shouldRejectConflictingContentForExistingExactJob() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var conflicting =
        new TranscodeJobSpec(
            specification.sessionId(),
            specification.jobRef(),
            specification.source(),
            specification.decision(),
            specification.execution(),
            List.of(rendition("default", 1280, 720, 2_500_000L)));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(specification, source);

    assertThatThrownBy(() -> engine.start(conflicting, source))
        .isInstanceOf(TranscodeException.class)
        .hasMessageContaining("conflicts");

    assertThat(processManager.startedKeys()).hasSize(1);
  }

  @Test
  @DisplayName("Should reject a lower generation after a newer generation is admitted")
  void shouldRejectLowerGenerationAfterNewerGenerationIsAdmitted() throws IOException {
    var jobId = UUID.randomUUID();
    var template = jobSpecification(List.of(defaultRendition()));
    var newer = withJobRef(template, new TranscodeJobRef(jobId, 2));
    var older = withJobRef(template, new TranscodeJobRef(jobId, 1));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(newer, source);

    assertThatThrownBy(() -> engine.start(older, source))
        .isInstanceOf(TranscodeException.class)
        .hasMessageContaining("stale");

    assertThat(processManager.startedKeys())
        .containsExactly(new FfmpegProcessKey(newer.jobRef(), "default"));
  }

  @Test
  @DisplayName("Should reject a different job that conflicts for the same session")
  void shouldRejectDifferentJobThatConflictsForSameSession() throws IOException {
    var first = jobSpecification(List.of(defaultRendition()));
    var conflicting = withJobRef(first, new TranscodeJobRef(UUID.randomUUID(), 1));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(first, source);

    assertThatThrownBy(() -> engine.start(conflicting, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.SESSION_CONFLICT));

    assertThat(processManager.startedKeys()).hasSize(1);
  }

  @Test
  @DisplayName("Should keep one job identity bound to its original session")
  void shouldKeepOneJobIdentityBoundToItsOriginalSession() throws IOException {
    var jobId = UUID.randomUUID();
    var original =
        withJobRef(jobSpecification(List.of(defaultRendition())), new TranscodeJobRef(jobId, 1));
    var differentSession =
        withJobRef(jobSpecification(List.of(defaultRendition())), new TranscodeJobRef(jobId, 2));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(original, source);

    assertThatThrownBy(() -> engine.start(differentSession, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.JOB_CONFLICT));
    assertThat(processManager.startedKeys()).hasSize(1);
    engine.stop(original.jobRef());
    engine.releaseObservation(original.jobRef());

    assertThatThrownBy(() -> engine.start(differentSession, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.JOB_CONFLICT));
  }

  @Test
  @DisplayName("Should fence the old process before starting a replacement generation")
  void shouldFenceOldProcessBeforeStartingReplacementGeneration() throws IOException {
    var jobId = UUID.randomUUID();
    var template = jobSpecification(List.of(defaultRendition()));
    var original = withJobRef(template, new TranscodeJobRef(jobId, 1));
    var replacement = withJobRef(template, new TranscodeJobRef(jobId, 2));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var originalKey = new FfmpegProcessKey(original.jobRef(), "default");
    processManager.onStart(
        (key, outputDirectory) -> {
          if (key.jobRef().equals(replacement.jobRef())) {
            assertThat(processManager.observe(originalKey).state())
                .isEqualTo(FfmpegProcessState.STOPPED);
          }
          writeStartupArtifacts(outputDirectory, "generation-" + key.jobRef().generation(), ".ts");
        });
    engine.start(original, source);

    engine.start(replacement, source);

    assertThat(processManager.observe(originalKey).state()).isEqualTo(FfmpegProcessState.STOPPED);
    assertThat(segmentStorage.readSegment(replacement.sessionId(), "segment0.ts"))
        .isEqualTo("generation-2".getBytes());
  }

  @Test
  @DisplayName(
      "Should preserve fallback segments when a delayed old stop arrives during replacement")
  void shouldPreserveFallbackSegmentsWhenDelayedOldStopArrivesDuringReplacement() throws Exception {
    var jobId = UUID.randomUUID();
    var original =
        withJobRef(jobSpecification(List.of(defaultRendition())), new TranscodeJobRef(jobId, 1));
    var execution = original.execution();
    var replacement =
        new TranscodeJobSpec(
            original.sessionId(),
            new TranscodeJobRef(jobId, 2),
            original.source(),
            original.decision(),
            new TranscodeExecutionParameters(
                execution.seekPosition(),
                execution.segmentDuration(),
                execution.framerate(),
                1,
                execution.startupTimeout()),
            original.renditions());
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var replacementSpawned = new CountDownLatch(1);
    var allowReplacement = new CountDownLatch(1);
    processManager.onStart(
        (key, outputDirectory) -> {
          if (key.jobRef().equals(original.jobRef())) {
            writeStartupArtifacts(outputDirectory, "original-zero", ".ts");
            return;
          }
          replacementSpawned.countDown();
          await(allowReplacement);
          Files.writeString(outputDirectory.resolve("stream.m3u8"), "#EXTM3U\n");
          Files.writeString(outputDirectory.resolve("segment1.ts"), "replacement-one");
        });
    engine.start(original, source);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var replacing = executor.submit(() -> engine.start(replacement, source));
      assertThat(replacementSpawned.await(1, TimeUnit.SECONDS)).isTrue();
      try {
        engine.stop(original.jobRef());

        assertThat(segmentStorage.readSegment(original.sessionId(), "segment0.ts"))
            .isEqualTo("original-zero".getBytes());
      } finally {
        allowReplacement.countDown();
      }
      assertThat(replacing.get(2, TimeUnit.SECONDS).state()).isEqualTo(TranscodeJobState.RUNNING);
    }
    assertThat(segmentStorage.readSegment(original.sessionId(), "segment0.ts"))
        .isEqualTo("original-zero".getBytes());
  }

  @Test
  @DisplayName("Should release a superseded generation after its replacement is published")
  void shouldReleaseSupersededGenerationAfterReplacementIsPublished() throws IOException {
    var jobId = UUID.randomUUID();
    var template = jobSpecification(List.of(defaultRendition()));
    var original = withJobRef(template, new TranscodeJobRef(jobId, 1));
    var replacement = withJobRef(template, new TranscodeJobRef(jobId, 2));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (key, outputDirectory) ->
            writeStartupArtifacts(
                outputDirectory, "generation-" + key.jobRef().generation(), ".ts"));
    engine.start(original, source);
    engine.start(replacement, source);

    assertThat(engine.inspect(original.jobRef()).state()).isEqualTo(TranscodeJobState.STOPPED);
    assertThat(engine.releaseObservation(original.jobRef())).isTrue();
    assertThat(engine.inspect(original.jobRef()).state()).isEqualTo(TranscodeJobState.ABSENT);
    assertThat(segmentStorage.readSegment(replacement.sessionId(), "segment0.ts"))
        .isEqualTo("generation-2".getBytes());
  }

  @Test
  @DisplayName("Should release stopped fallback after a different job publishes for the session")
  void shouldReleaseStoppedFallbackAfterDifferentJobPublishesForSession() throws IOException {
    var original = jobSpecification(List.of(defaultRendition()));
    var failedReplacement =
        new TranscodeJobSpec(
            original.sessionId(),
            new TranscodeJobRef(original.jobRef().jobId(), 2),
            original.source(),
            original.decision(),
            jobSpecification(List.of(defaultRendition()), Duration.ofMillis(50)).execution(),
            original.renditions());
    var nextJob =
        new TranscodeJobSpec(
            original.sessionId(),
            new TranscodeJobRef(UUID.randomUUID(), 1),
            original.source(),
            original.decision(),
            original.execution(),
            original.renditions());
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (key, outputDirectory) -> {
          if (!key.jobRef().equals(failedReplacement.jobRef())) {
            writeStartupArtifacts(outputDirectory, key.jobRef().jobId().toString(), ".ts");
          }
        });
    engine.start(original, source);
    assertThatThrownBy(() -> engine.start(failedReplacement, source))
        .isInstanceOf(TranscodeException.class);
    engine.stop(failedReplacement.jobRef());

    engine.start(nextJob, source);

    assertThat(engine.releaseObservation(original.jobRef())).isTrue();
  }

  @Test
  @DisplayName("Should report cleanup pending when a superseded process cannot be stopped")
  void shouldReportCleanupPendingWhenSupersededProcessCannotBeStopped() throws IOException {
    var jobId = UUID.randomUUID();
    var template = jobSpecification(List.of(defaultRendition()));
    var original = withJobRef(template, new TranscodeJobRef(jobId, 1));
    var replacement = withJobRef(template, new TranscodeJobRef(jobId, 2));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "original", ".ts"));
    engine.start(original, source);
    processManager.failNextStop(new TranscodeException("stop failed"));

    assertThatThrownBy(() -> engine.start(replacement, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.CLEANUP_PENDING));
    assertThat(processManager.isRunning(original.jobRef())).isTrue();
    assertThat(segmentStorage.readSegment(original.sessionId(), "segment0.ts"))
        .isEqualTo("original".getBytes());
  }

  @Test
  @DisplayName("Should preserve the prior published generation when replacement startup fails")
  void shouldPreservePriorPublishedGenerationWhenReplacementStartupFails() throws IOException {
    var jobId = UUID.randomUUID();
    var template = jobSpecification(List.of(defaultRendition()));
    var original = withJobRef(template, new TranscodeJobRef(jobId, 1));
    var replacement =
        withJobRef(
            jobSpecification(List.of(defaultRendition()), Duration.ofMillis(50)),
            new TranscodeJobRef(jobId, 2));
    replacement =
        new TranscodeJobSpec(
            original.sessionId(),
            replacement.jobRef(),
            original.source(),
            original.decision(),
            replacement.execution(),
            original.renditions());
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (key, outputDirectory) -> {
          if (key.jobRef().equals(original.jobRef())) {
            writeStartupArtifacts(outputDirectory, "original", ".ts");
          }
        });
    engine.start(original, source);

    var failedReplacement = replacement;
    assertThatThrownBy(() -> engine.start(failedReplacement, source))
        .isInstanceOf(TranscodeException.class)
        .hasMessageContaining("timed out");

    assertThat(segmentStorage.readSegment(original.sessionId(), "segment0.ts"))
        .isEqualTo("original".getBytes());

    assertThat(engine.stop(original.jobRef()).state()).isEqualTo(TranscodeJobState.STOPPED);
    assertThat(segmentStorage.segmentExists(original.sessionId(), "segment0.ts")).isTrue();
    engine.shutdown();

    assertThat(segmentStorage.segmentExists(original.sessionId(), "segment0.ts")).isFalse();
  }

  @Test
  @DisplayName("Should preserve fallback when supersession is admitted during failure cleanup")
  void shouldPreserveFallbackWhenSupersessionIsAdmittedDuringFailureCleanup() throws Exception {
    var jobId = UUID.randomUUID();
    var original =
        withJobRef(jobSpecification(List.of(defaultRendition())), new TranscodeJobRef(jobId, 1));
    var replacementTemplate = jobSpecification(List.of(defaultRendition()), Duration.ofMillis(50));
    var replacement =
        new TranscodeJobSpec(
            original.sessionId(),
            new TranscodeJobRef(jobId, 2),
            original.source(),
            original.decision(),
            replacementTemplate.execution(),
            original.renditions());
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (key, outputDirectory) -> {
          if (key.jobRef().equals(original.jobRef())) {
            writeStartupArtifacts(outputDirectory, "original", ".ts");
          }
        });
    engine.start(original, source);
    processManager.failProcess(
        new FfmpegProcessKey(original.jobRef(), defaultRendition().label()), 42);
    var failureStopEntered = new CountDownLatch(1);
    var releaseFailureStop = new CountDownLatch(1);
    var stopCalls = new AtomicInteger();
    processManager.onStop(
        () -> {
          if (stopCalls.incrementAndGet() == 1) {
            failureStopEntered.countDown();
            await(releaseFailureStop);
          }
        });

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var inspection = executor.submit(() -> engine.inspect(original.jobRef()));
      assertThat(failureStopEntered.await(1, TimeUnit.SECONDS)).isTrue();
      var replacementStart = executor.submit(() -> engine.start(replacement, source));
      assertThat(awaitState(replacement.jobRef(), TranscodeJobState.ADMITTING)).isTrue();
      releaseFailureStop.countDown();

      inspection.get(2, TimeUnit.SECONDS);
      assertThatThrownBy(() -> replacementStart.get(2, TimeUnit.SECONDS))
          .hasCauseInstanceOf(TranscodeException.class);
    }

    assertThat(segmentStorage.readSegment(original.sessionId(), "segment0.ts"))
        .isEqualTo("original".getBytes());
  }

  @Test
  @DisplayName("Should discard retained unpublished staging before starting a newer generation")
  void shouldDiscardRetainedUnpublishedStagingBeforeStartingNewerGeneration() throws IOException {
    var jobId = UUID.randomUUID();
    var original =
        withJobRef(
            jobSpecification(List.of(defaultRendition()), Duration.ofMillis(50)),
            new TranscodeJobRef(jobId, 1));
    var replacement = withJobRef(original, new TranscodeJobRef(jobId, 2));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var originalStaging = new AtomicReference<Path>();
    processManager.onStart((_, outputDirectory) -> originalStaging.set(outputDirectory));
    processManager.failNextStop(new TranscodeException("cleanup failed"));
    assertThatThrownBy(() -> engine.start(original, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.CLEANUP_PENDING));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "replacement", ".ts"));

    engine.start(replacement, source);

    assertThat(originalStaging.get()).doesNotExist();
    assertThat(segmentStorage.readSegment(replacement.sessionId(), "segment0.ts"))
        .isEqualTo("replacement".getBytes());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(value = SubtitleMode.class, names = "EXCLUDE", mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should reject an unsupported subtitle mode before starting any process")
  void shouldRejectUnsupportedSubtitleModeBeforeStartingAnyProcess(SubtitleMode subtitleMode)
      throws IOException {
    var template = jobSpecification(List.of(defaultRendition()));
    var decision = template.decision();
    var unsupportedSubtitle =
        new SubtitleDecision(
            subtitleMode, Optional.of("ass"), OptionalInt.of(0), Optional.of("eng"));
    var unsupportedDecision =
        new TranscodeDecision(
            decision.transcodeMode(),
            decision.videoCodecFamily(),
            decision.audioDecision(),
            unsupportedSubtitle,
            decision.containerFormat(),
            decision.needsKeyframeAlignment());
    var specification = withDecision(template, unsupportedDecision);
    var source = Files.createFile(tempDir.resolve("movie.mkv"));

    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.INVALID_SPECIFICATION));

    assertThat(processManager.startedKeys()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName("Should use the copy video path without probing an encoder")
  void shouldUseCopyVideoPathWithoutProbingEncoder(TranscodeMode mode) throws IOException {
    var specification = withMode(jobSpecification(List.of(defaultRendition())), mode);
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));

    var observation = engine.start(specification, source);

    assertThat(observation.state()).isEqualTo(TranscodeJobState.RUNNING);
    assertThat(processManager.startedKeys())
        .containsExactly(new FfmpegProcessKey(specification.jobRef(), "default"));
  }

  @Test
  @DisplayName("Should reject a completed rendition whose playlist has no end marker")
  void shouldRejectCompletedRenditionWhosePlaylistHasNoEndMarker() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (key, outputDirectory) -> {
          writeStartupArtifacts(outputDirectory, "segment", ".ts");
          processManager.completeProcess(key);
        });

    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.STARTUP_FAILED));

    assertThat(segmentStorage.segmentExists(specification.sessionId(), "segment0.ts")).isFalse();
  }

  @Test
  @DisplayName("Should fail and withdraw a job when completed playlist inspection fails")
  void shouldFailAndWithdrawJobWhenCompletedPlaylistInspectionFails() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var outputDirectory = new AtomicReference<Path>();
    processManager.onStart(
        (_, output) -> {
          outputDirectory.set(output);
          writeStartupArtifacts(output, "segment", ".ts");
        });
    engine.start(specification, source);
    Files.write(outputDirectory.get().resolve("stream.m3u8"), new byte[1_048_577]);
    processManager.completeProcess(new FfmpegProcessKey(specification.jobRef(), "default"));

    var failed = engine.inspect(specification.jobRef());

    assertThat(failed.state()).isEqualTo(TranscodeJobState.FAILED);
    assertThat(segmentStorage.segmentExists(specification.sessionId(), "segment0.ts")).isFalse();
  }

  @Test
  @DisplayName("Should publish a cleanly completed job only when every playlist has an end marker")
  void shouldPublishCleanlyCompletedJobOnlyWhenEveryPlaylistHasEndMarker() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (key, outputDirectory) -> {
          Files.writeString(outputDirectory.resolve("stream.m3u8"), "#EXTM3U\n#EXT-X-ENDLIST\n");
          Files.writeString(outputDirectory.resolve("segment0.ts"), "segment");
          processManager.completeProcess(key);
        });

    var observation = engine.start(specification, source);

    assertThat(observation.state()).isEqualTo(TranscodeJobState.COMPLETED);
    assertThat(observation.renditions())
        .extracting(RenditionObservation::state)
        .containsExactly(RenditionState.COMPLETED);
    assertThat(segmentStorage.readSegment(specification.sessionId(), "segment0.ts"))
        .isEqualTo("segment".getBytes());
  }

  @Test
  @DisplayName("Should stop a complete job exactly and make repeated stop idempotent")
  void shouldStopCompleteJobExactlyAndMakeRepeatedStopIdempotent() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(specification, source);

    var stopped = engine.stop(specification.jobRef());
    var repeated = engine.stop(specification.jobRef());

    assertThat(stopped.state()).isEqualTo(TranscodeJobState.STOPPED);
    assertThat(stopped.renditions())
        .extracting(RenditionObservation::state)
        .containsExactly(RenditionState.STOPPED);
    assertThat(repeated).isEqualTo(stopped);
    assertThat(processManager.isRunning(specification.jobRef())).isFalse();
    assertThat(segmentStorage.segmentExists(specification.sessionId(), "segment0.ts")).isFalse();
  }

  @Test
  @DisplayName("Should treat stop and release for an unknown exact job as already complete")
  void shouldTreatStopAndReleaseForUnknownExactJobAsAlreadyComplete() {
    var unknown = new TranscodeJobRef(UUID.randomUUID(), 1);

    assertThat(engine.stop(unknown).state()).isEqualTo(TranscodeJobState.ABSENT);
    assertThat(engine.releaseObservation(unknown)).isTrue();
  }

  @Test
  @DisplayName("Should retain a published job when stop cleanup fails and complete on retry")
  void shouldRetainPublishedJobWhenStopCleanupFailsAndCompleteOnRetry() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(specification, source);
    processManager.failNextStop(new TranscodeException("stop failed"));

    assertThatThrownBy(() -> engine.stop(specification.jobRef()))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.CLEANUP_PENDING));
    assertThat(segmentStorage.readSegment(specification.sessionId(), "segment0.ts"))
        .isEqualTo("segment".getBytes());
    assertThat(engine.releaseObservation(specification.jobRef())).isFalse();

    assertThat(engine.stop(specification.jobRef()).state()).isEqualTo(TranscodeJobState.STOPPED);
    assertThat(segmentStorage.segmentExists(specification.sessionId(), "segment0.ts")).isFalse();
    assertThat(engine.releaseObservation(specification.jobRef())).isTrue();
  }

  @Test
  @DisplayName("Should fail and withdraw the whole job when one running rendition fails")
  void shouldFailAndWithdrawWholeJobWhenOneRunningRenditionFails() throws IOException {
    var specification =
        jobSpecification(
            List.of(
                rendition("480p", 854, 480, 1_500_000L),
                rendition("1080p", 1920, 1080, 5_000_000L)));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (key, outputDirectory) ->
            writeStartupArtifacts(outputDirectory, key.renditionLabel(), ".ts"));
    engine.start(specification, source);
    processManager.failProcess(new FfmpegProcessKey(specification.jobRef(), "1080p"), 42);

    var failed = engine.inspect(specification.jobRef());

    assertThat(failed.state()).isEqualTo(TranscodeJobState.FAILED);
    assertThat(processManager.isRunning(specification.jobRef())).isFalse();
    assertThat(segmentStorage.segmentExists(specification.sessionId(), "480p/segment0.ts"))
        .isFalse();
  }

  @Test
  @DisplayName("Should retain a failed publication when inspection cleanup fails and retry")
  void shouldRetainFailedPublicationWhenInspectionCleanupFailsAndRetry() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var key = new FfmpegProcessKey(specification.jobRef(), "default");
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(specification, source);
    processManager.failProcess(key, 42);
    processManager.failNextStop(new TranscodeException("failure cleanup failed"));

    assertThatThrownBy(() -> engine.inspect(specification.jobRef()))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.CLEANUP_PENDING));
    assertThat(segmentStorage.readSegment(specification.sessionId(), "segment0.ts"))
        .isEqualTo("segment".getBytes());
    assertThat(engine.releaseObservation(specification.jobRef())).isFalse();

    assertThat(engine.inspect(specification.jobRef()).state()).isEqualTo(TranscodeJobState.FAILED);
    assertThat(segmentStorage.segmentExists(specification.sessionId(), "segment0.ts")).isFalse();
    assertThat(engine.releaseObservation(specification.jobRef())).isTrue();
  }

  @Test
  @DisplayName("Should preserve stopped state when failure inspection races explicit stop")
  void shouldPreserveStoppedStateWhenFailureInspectionRacesExplicitStop() throws Exception {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(specification, source);
    processManager.failProcess(new FfmpegProcessKey(specification.jobRef(), "default"), 23);
    var inspectStopEntered = new CountDownLatch(1);
    var releaseInspect = new CountDownLatch(1);
    var stopCalls = new AtomicInteger();
    processManager.onStop(
        () -> {
          if (stopCalls.incrementAndGet() == 1) {
            inspectStopEntered.countDown();
            await(releaseInspect);
          }
        });

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var inspecting = executor.submit(() -> engine.inspect(specification.jobRef()));
      assertThat(inspectStopEntered.await(1, TimeUnit.SECONDS)).isTrue();
      var stopping = executor.submit(() -> engine.stop(specification.jobRef()));
      assertThat(stopping.get(2, TimeUnit.SECONDS).state()).isEqualTo(TranscodeJobState.STOPPED);
      releaseInspect.countDown();

      assertThat(inspecting.get(2, TimeUnit.SECONDS).state()).isEqualTo(TranscodeJobState.STOPPED);
    }
    assertThat(engine.inspect(specification.jobRef()).state()).isEqualTo(TranscodeJobState.STOPPED);
  }

  @Test
  @DisplayName("Should preserve stopped state when healthy inspection races explicit stop")
  void shouldPreserveStoppedStateWhenHealthyInspectionRacesExplicitStop() throws Exception {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(specification, source);
    var healthyObservationCaptured = new CountDownLatch(1);
    var releaseInspection = new CountDownLatch(1);
    var observations = new AtomicInteger();
    processManager.afterObserve(
        _ -> {
          if (observations.incrementAndGet() == 2) {
            healthyObservationCaptured.countDown();
            await(releaseInspection);
          }
        });

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var inspecting = executor.submit(() -> engine.inspect(specification.jobRef()));
      assertThat(healthyObservationCaptured.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(engine.stop(specification.jobRef()).state()).isEqualTo(TranscodeJobState.STOPPED);
      releaseInspection.countDown();

      assertThat(inspecting.get(2, TimeUnit.SECONDS).state()).isEqualTo(TranscodeJobState.STOPPED);
    }
    assertThat(engine.inspect(specification.jobRef()).state()).isEqualTo(TranscodeJobState.STOPPED);
  }

  @Test
  @DisplayName("Should preserve published fallback when stale inspection finishes after fencing")
  void shouldPreservePublishedFallbackWhenStaleInspectionFinishesAfterFencing() throws Exception {
    var jobId = UUID.randomUUID();
    var original =
        withJobRef(jobSpecification(List.of(defaultRendition())), new TranscodeJobRef(jobId, 1));
    var replacementTemplate = jobSpecification(List.of(defaultRendition()), Duration.ofMillis(50));
    var replacement =
        new TranscodeJobSpec(
            original.sessionId(),
            new TranscodeJobRef(jobId, 2),
            original.source(),
            original.decision(),
            replacementTemplate.execution(),
            original.renditions());
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "original", ".ts"));
    engine.start(original, source);
    var inspectionEntered = new CountDownLatch(1);
    var releaseInspection = new CountDownLatch(1);
    var replacementSpawned = new CountDownLatch(1);
    var releaseReplacement = new CountDownLatch(1);
    var blockInspection = new AtomicBoolean(true);
    processManager.onObserve(
        key -> {
          if (key.jobRef().equals(original.jobRef()) && blockInspection.getAndSet(false)) {
            inspectionEntered.countDown();
            await(releaseInspection);
          }
        });
    processManager.onStart(
        (key, _) -> {
          if (key.jobRef().equals(replacement.jobRef())) {
            replacementSpawned.countDown();
            await(releaseReplacement);
          }
        });

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var inspection = executor.submit(() -> engine.inspect(original.jobRef()));
      assertThat(inspectionEntered.await(1, TimeUnit.SECONDS)).isTrue();
      var replacementStart = executor.submit(() -> engine.start(replacement, source));
      assertThat(replacementSpawned.await(1, TimeUnit.SECONDS)).isTrue();
      releaseInspection.countDown();
      assertThat(inspection.get(2, TimeUnit.SECONDS).state()).isEqualTo(TranscodeJobState.STOPPED);
      releaseReplacement.countDown();
      assertThatThrownBy(() -> replacementStart.get(2, TimeUnit.SECONDS))
          .hasCauseInstanceOf(TranscodeException.class);
    }

    assertThat(segmentStorage.readSegment(original.sessionId(), "segment0.ts"))
        .isEqualTo("original".getBytes());
  }

  @Test
  @DisplayName("Should release only terminal observations while retaining generation high-water")
  void shouldReleaseOnlyTerminalObservationsWhileRetainingGenerationHighWater() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(specification, source);

    assertThat(engine.releaseObservation(specification.jobRef())).isFalse();
    engine.stop(specification.jobRef());
    assertThat(engine.releaseObservation(specification.jobRef())).isTrue();
    assertThat(engine.inspect(specification.jobRef()).state()).isEqualTo(TranscodeJobState.ABSENT);
    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.STALE_GENERATION));
  }

  @Test
  @DisplayName("Should retain a terminal observation when process release is refused and retry")
  void shouldRetainTerminalObservationWhenProcessReleaseIsRefusedAndRetry() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(specification, source);
    engine.stop(specification.jobRef());
    processManager.refuseNextRelease();

    assertThat(engine.releaseObservation(specification.jobRef())).isFalse();
    assertThat(engine.inspect(specification.jobRef()).state()).isEqualTo(TranscodeJobState.STOPPED);
    assertThat(engine.releaseObservation(specification.jobRef())).isTrue();
    assertThat(engine.inspect(specification.jobRef()).state()).isEqualTo(TranscodeJobState.ABSENT);
  }

  @Test
  @DisplayName("Should stop every job and reject new admission after shutdown")
  void shouldStopEveryJobAndRejectNewAdmissionAfterShutdown() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(specification, source);

    engine.shutdown();

    assertThat(processManager.isRunning(specification.jobRef())).isFalse();
    assertThat(engine.inspect(specification.jobRef()).state()).isEqualTo(TranscodeJobState.STOPPED);
    assertThat(segmentStorage.segmentExists(specification.sessionId(), "segment0.ts")).isFalse();
    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.SHUTTING_DOWN));
  }

  @Test
  @DisplayName("Should retain jobs when force-stop fails during shutdown and complete on retry")
  void shouldRetainJobsWhenForceStopFailsDuringShutdownAndCompleteOnRetry() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(specification, source);
    processManager.failNextForceStop(new TranscodeException("force-stop failed"));

    assertThatThrownBy(engine::shutdown)
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.CLEANUP_PENDING));
    assertThat(processManager.isRunning(specification.jobRef())).isTrue();
    assertThat(segmentStorage.readSegment(specification.sessionId(), "segment0.ts"))
        .isEqualTo("segment".getBytes());

    engine.shutdown();

    assertThat(processManager.isRunning(specification.jobRef())).isFalse();
    assertThat(engine.inspect(specification.jobRef()).state()).isEqualTo(TranscodeJobState.STOPPED);
    assertThat(segmentStorage.segmentExists(specification.sessionId(), "segment0.ts")).isFalse();
  }

  @Test
  @DisplayName("Should retain a completed observation until its publication is withdrawn")
  void shouldRetainCompletedObservationUntilPublicationIsWithdrawn() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (key, outputDirectory) -> {
          Files.writeString(outputDirectory.resolve("stream.m3u8"), "#EXTM3U\n#EXT-X-ENDLIST\n");
          Files.writeString(outputDirectory.resolve("segment0.ts"), "segment");
          processManager.completeProcess(key);
        });
    engine.start(specification, source);

    assertThat(engine.releaseObservation(specification.jobRef())).isFalse();
    assertThat(segmentStorage.segmentExists(specification.sessionId(), "segment0.ts")).isTrue();
    engine.stop(specification.jobRef());
    assertThat(engine.releaseObservation(specification.jobRef())).isTrue();
  }

  @Test
  @DisplayName("Should retain and retry staged cleanup after startup compensation fails")
  void shouldRetainAndRetryStagedCleanupAfterStartupCompensationFails() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()), Duration.ofMillis(50));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var stagingDirectory = new AtomicReference<Path>();
    processManager.onStart((_, outputDirectory) -> stagingDirectory.set(outputDirectory));
    processManager.failNextStop(new TranscodeException("cleanup failed"));

    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.CLEANUP_PENDING));
    assertThat(engine.releaseObservation(specification.jobRef())).isFalse();

    engine.stop(specification.jobRef());

    assertThat(stagingDirectory.get()).doesNotExist();
    assertThat(engine.releaseObservation(specification.jobRef())).isTrue();
  }

  @Test
  @DisplayName("Should make concurrent stops idempotent for retained staging")
  void shouldMakeConcurrentStopsIdempotentForRetainedStaging() throws Exception {
    var blockingStorage = new BlockingDiscardStorage(tempDir.resolve("blocking-segments"));
    segmentStorage = blockingStorage;
    engine =
        LocalTranscodeEngine.builder()
            .commandBuilder(new FfmpegCommandBuilder("ffmpeg"))
            .processManager(processManager)
            .segmentStorage(segmentStorage)
            .capabilityService(
                new TranscodeCapabilityService(
                    "ffmpeg",
                    _ -> {
                      throw new UnsupportedOperationException();
                    }))
            .build();
    var specification = jobSpecification(List.of(defaultRendition()), Duration.ofMillis(50));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.failNextStop(new TranscodeException("cleanup failed"));
    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOf(TranscodeEngineException.class);
    blockingStorage.blockDiscards();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> engine.stop(specification.jobRef()));
      var second = executor.submit(() -> engine.stop(specification.jobRef()));
      try {
        assertThat(blockingStorage.awaitBothDiscards()).isFalse();
      } finally {
        blockingStorage.releaseDiscards();
      }

      assertThat(first.get(2, TimeUnit.SECONDS).state()).isEqualTo(TranscodeJobState.STOPPED);
      assertThat(second.get(2, TimeUnit.SECONDS).state()).isEqualTo(TranscodeJobState.STOPPED);
    }
  }

  @Test
  @DisplayName("Should discard retained staging when engine shuts down after cleanup failure")
  void shouldDiscardRetainedStagingWhenEngineShutsDownAfterCleanupFailure() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()), Duration.ofMillis(50));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var stagingDirectory = new AtomicReference<Path>();
    processManager.onStart((_, outputDirectory) -> stagingDirectory.set(outputDirectory));
    processManager.failNextStop(new TranscodeException("cleanup failed"));
    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.CLEANUP_PENDING));

    engine.shutdown();

    assertThat(stagingDirectory.get()).doesNotExist();
  }

  @Test
  @DisplayName("Should prevent late rendition spawn when stop races startup")
  void shouldPreventLateRenditionSpawnWhenStopRacesStartup() throws Exception {
    var specification =
        jobSpecification(
            List.of(
                rendition("480p", 854, 480, 1_500_000L),
                rendition("1080p", 1920, 1080, 5_000_000L)));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    var firstSpawned = new CountDownLatch(1);
    var stopEntered = new CountDownLatch(1);
    var releaseSpawn = new CountDownLatch(1);
    processManager.onStop(stopEntered::countDown);
    processManager.onStart(
        (key, _) -> {
          if (key.renditionLabel().equals("480p")) {
            firstSpawned.countDown();
            await(releaseSpawn);
          }
        });

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var starting = executor.submit(() -> engine.start(specification, source));
      assertThat(firstSpawned.await(1, TimeUnit.SECONDS)).isTrue();
      var stopping = executor.submit(() -> engine.stop(specification.jobRef()));
      assertThat(stopEntered.await(1, TimeUnit.SECONDS)).isTrue();
      releaseSpawn.countDown();

      assertThat(stopping.get(2, TimeUnit.SECONDS).state()).isEqualTo(TranscodeJobState.STOPPED);
      assertThatThrownBy(() -> starting.get(2, TimeUnit.SECONDS))
          .hasCauseInstanceOf(TranscodeEngineException.class);
    }
    assertThat(processManager.startedKeys())
        .containsExactly(new FfmpegProcessKey(specification.jobRef(), "480p"));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"stream.m3u8", "segment7.m4s", "init.mp4"})
  @DisplayName("Should require every nonempty FMP4 startup artifact")
  void shouldRequireEveryNonemptyFmp4StartupArtifact(String emptyArtifact) throws IOException {
    var specification = fmp4Specification(Duration.ofMillis(50));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> {
          Files.writeString(outputDirectory.resolve("stream.m3u8"), "#EXTM3U\n");
          Files.writeString(outputDirectory.resolve("segment7.m4s"), "segment-seven");
          Files.writeString(outputDirectory.resolve("init.mp4"), "init");
          Files.write(outputDirectory.resolve(emptyArtifact), new byte[0]);
        });

    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.STARTUP_FAILED));
  }

  @Test
  @DisplayName("Should publish FMP4 after its init and first requested segment are ready")
  void shouldPublishFmp4AfterInitAndFirstRequestedSegmentAreReady() throws IOException {
    var specification = fmp4Specification(Duration.ofSeconds(1));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> {
          Files.writeString(outputDirectory.resolve("stream.m3u8"), "#EXTM3U\n");
          Files.writeString(outputDirectory.resolve("segment7.m4s"), "segment-seven");
          Files.writeString(outputDirectory.resolve("init.mp4"), "init");
        });

    engine.start(specification, source);

    assertThat(segmentStorage.readSegment(specification.sessionId(), "segment7.m4s"))
        .isEqualTo("segment-seven".getBytes());
  }

  @Test
  @DisplayName("Should return the retained terminal result for a duplicate exact start")
  void shouldReturnRetainedTerminalResultForDuplicateExactStart() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));
    processManager.onStart(
        (_, outputDirectory) -> writeStartupArtifacts(outputDirectory, "segment", ".ts"));
    engine.start(specification, source);
    var stopped = engine.stop(specification.jobRef());

    var duplicate = engine.start(specification, source);

    assertThat(duplicate).isEqualTo(stopped);
    assertThat(processManager.startedKeys()).hasSize(1);
  }

  @Test
  @DisplayName("Should repeat the original failure for a duplicate exact start")
  void shouldRepeatOriginalFailureForDuplicateExactStart() throws IOException {
    var specification = jobSpecification(List.of(defaultRendition()), Duration.ofMillis(50));
    var source = Files.createFile(tempDir.resolve("movie.mkv"));

    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.STARTUP_FAILED));
    assertThatThrownBy(() -> engine.start(specification, source))
        .isInstanceOfSatisfying(
            TranscodeEngineException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(TranscodeEngineException.Reason.STARTUP_FAILED));
    assertThat(processManager.startedKeys()).hasSize(1);
  }

  private static TranscodeJobSpec jobSpecification(List<RenditionSpec> renditions) {
    return jobSpecification(renditions, Duration.ofSeconds(1));
  }

  private static TranscodeJobSpec jobSpecification(
      List<RenditionSpec> renditions, Duration startupTimeout) {
    return TranscodeJobSpec.builder()
        .sessionId(UUID.randomUUID())
        .jobRef(new TranscodeJobRef(UUID.randomUUID(), 1))
        .source(new MediaSourceRef(UUID.randomUUID(), "Movies/movie.mkv"))
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
                .segmentDuration(6)
                .framerate(23.976)
                .startupTimeout(startupTimeout)
                .build())
        .renditions(renditions)
        .build();
  }

  private static TranscodeJobSpec fmp4Specification(Duration startupTimeout) {
    var template = jobSpecification(List.of(defaultRendition()), startupTimeout);
    var decision = template.decision();
    var execution = template.execution();
    return new TranscodeJobSpec(
        template.sessionId(),
        template.jobRef(),
        template.source(),
        new TranscodeDecision(
            decision.transcodeMode(),
            decision.videoCodecFamily(),
            decision.audioDecision(),
            decision.subtitleDecision(),
            ContainerFormat.FMP4,
            decision.needsKeyframeAlignment()),
        new TranscodeExecutionParameters(
            execution.seekPosition(),
            execution.segmentDuration(),
            execution.framerate(),
            7,
            startupTimeout),
        template.renditions());
  }

  private static RenditionSpec defaultRendition() {
    return rendition("default", 1920, 1080, 5_000_000L);
  }

  private static TranscodeJobSpec withJobRef(
      TranscodeJobSpec specification, TranscodeJobRef jobRef) {
    return new TranscodeJobSpec(
        specification.sessionId(),
        jobRef,
        specification.source(),
        specification.decision(),
        specification.execution(),
        specification.renditions());
  }

  private static TranscodeJobSpec withDecision(
      TranscodeJobSpec specification, TranscodeDecision decision) {
    return new TranscodeJobSpec(
        specification.sessionId(),
        specification.jobRef(),
        specification.source(),
        decision,
        specification.execution(),
        specification.renditions());
  }

  private static TranscodeJobSpec withMode(TranscodeJobSpec specification, TranscodeMode mode) {
    var decision = specification.decision();
    return withDecision(
        specification,
        new TranscodeDecision(
            mode,
            decision.videoCodecFamily(),
            decision.audioDecision(),
            decision.subtitleDecision(),
            decision.containerFormat(),
            decision.needsKeyframeAlignment()));
  }

  private static RenditionSpec rendition(String label, int width, int height, long bitrate) {
    return RenditionSpec.builder()
        .label(label)
        .width(width)
        .height(height)
        .videoBitrate(bitrate)
        .build();
  }

  private static void writeStartupArtifacts(Path outputDirectory, String contents, String extension)
      throws IOException {
    Files.writeString(outputDirectory.resolve("stream.m3u8"), "#EXTM3U\n");
    Files.writeString(outputDirectory.resolve("segment0" + extension), contents);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new TranscodeException("Test interrupted", exception);
    }
  }

  private boolean awaitState(TranscodeJobRef jobRef, TranscodeJobState expected) {
    var deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
    while (System.nanoTime() < deadline) {
      if (engine.inspect(jobRef).state() == expected) {
        return true;
      }
      Thread.yield();
    }
    return false;
  }

  private static final class BlockingDiscardStorage extends LocalSegmentStorage {
    private final CountDownLatch bothDiscards = new CountDownLatch(2);
    private final CountDownLatch releaseDiscards = new CountDownLatch(1);
    private volatile boolean blocking;

    private BlockingDiscardStorage(Path baseDir) {
      super(baseDir);
    }

    private void blockDiscards() {
      blocking = true;
    }

    private boolean awaitBothDiscards() throws InterruptedException {
      return bothDiscards.await(1, TimeUnit.SECONDS);
    }

    private void releaseDiscards() {
      releaseDiscards.countDown();
    }

    @Override
    public void discard(SegmentGeneration generation) {
      if (blocking) {
        bothDiscards.countDown();
        await(releaseDiscards);
      }
      super.discard(generation);
    }
  }
}
