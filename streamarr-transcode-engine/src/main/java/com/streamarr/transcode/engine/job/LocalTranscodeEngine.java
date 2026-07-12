package com.streamarr.transcode.engine.job;

import com.streamarr.transcode.engine.error.TranscodeException;
import com.streamarr.transcode.engine.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessKey;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessManager;
import com.streamarr.transcode.engine.ffmpeg.TranscodeCapabilityService;
import com.streamarr.transcode.engine.model.RenditionJob;
import com.streamarr.transcode.engine.model.RenditionObservation;
import com.streamarr.transcode.engine.model.RenditionRequest;
import com.streamarr.transcode.engine.model.RenditionSpec;
import com.streamarr.transcode.engine.model.RenditionState;
import com.streamarr.transcode.engine.model.SubtitleMode;
import com.streamarr.transcode.engine.model.TranscodeJobObservation;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import com.streamarr.transcode.engine.model.TranscodeJobSpec;
import com.streamarr.transcode.engine.model.TranscodeJobState;
import com.streamarr.transcode.engine.model.TranscodeMode;
import com.streamarr.transcode.engine.segment.LocalSegmentStorage;
import com.streamarr.transcode.engine.segment.SegmentGeneration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;

@Builder
public class LocalTranscodeEngine {

  private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(25);
  private final FfmpegCommandBuilder commandBuilder;
  private final FfmpegProcessManager processManager;
  private final LocalSegmentStorage segmentStorage;
  private final TranscodeCapabilityService capabilityService;

  @Builder.Default
  private final ConcurrentHashMap<TranscodeJobRef, JobRun> runs = new ConcurrentHashMap<>();

  @Builder.Default
  private final ConcurrentHashMap<UUID, Long> highestGenerationByJobId = new ConcurrentHashMap<>();

  @Builder.Default
  private final ConcurrentHashMap<UUID, UUID> sessionIdByJobId = new ConcurrentHashMap<>();

  @Builder.Default
  private final ConcurrentHashMap<UUID, UUID> sessionOwnerBySessionId = new ConcurrentHashMap<>();

  @Builder.Default
  private final ConcurrentHashMap<UUID, TranscodeJobRef> publications = new ConcurrentHashMap<>();

  @Builder.Default private final Object lifecycleMonitor = new Object();
  private volatile boolean shuttingDown;

  public TranscodeJobObservation start(TranscodeJobSpec specification, Path resolvedSource) {
    validateSpecification(specification);
    var admission = admit(specification, resolvedSource);
    if (!admission.owner()) {
      var existing = admission.run();
      if (!existing.startup().isDone() || existing.startup().isCompletedExceptionally()) {
        return await(existing.startup());
      }
      if (existing.cleanupComplete || isPublished(existing)) {
        return existing.observation.get();
      }
      return await(existing.startup());
    }
    var run = admission.run();
    SegmentGeneration generation = null;
    try {
      fenceSuperseded(admission.superseded());
      assertAdmitted(run);
      generation =
          segmentStorage.prepareGeneration(specification.sessionId(), specification.jobRef());
      run.generation.set(generation);
      for (var rendition : specification.renditions()) {
        assertAdmitted(run);
        startRendition(specification, resolvedSource, generation, rendition);
        assertAdmitted(run);
      }
      awaitReadiness(run, generation);
      var started = healthyObservation(specification, generation);
      synchronized (lifecycleMonitor) {
        assertAdmitted(run);
        segmentStorage.publish(generation);
        publications.put(specification.sessionId(), specification.jobRef());
        run.observation.set(started);
      }
      run.startup().complete(started);
      return started;
    } catch (RuntimeException exception) {
      var failure = compensate(run, generation, exception);
      run.observation.set(
          observation(specification, TranscodeJobState.FAILED, RenditionState.FAILED));
      run.startup().completeExceptionally(failure);
      throw failure;
    }
  }

  public TranscodeJobObservation inspect(TranscodeJobRef jobRef) {
    var run = runs.get(jobRef);
    if (run == null) {
      return absent(jobRef);
    }
    if (run.observation.get().state() != TranscodeJobState.RUNNING) {
      return run.observation.get();
    }
    try {
      assertProcessesHealthy(run.specification, run.generation.get());
      var healthy = healthyObservation(run.specification, run.generation.get());
      synchronized (lifecycleMonitor) {
        if (run.observation.get().state() == TranscodeJobState.RUNNING) {
          run.observation.set(healthy);
        }
        return run.observation.get();
      }
    } catch (RuntimeException exception) {
      if (exception instanceof TranscodeEngineException engineException
          && engineException.reason() != TranscodeEngineException.Reason.STARTUP_FAILED) {
        throw engineException;
      }
      return fail(run);
    }
  }

  private TranscodeJobObservation fail(JobRun run) {
    var jobRef = run.specification.jobRef();
    try {
      processManager.stopJob(jobRef);
      if (processManager.isRunning(jobRef)) {
        throw new IllegalStateException("Exact transcode processes remain active");
      }
      synchronized (run.monitor) {
        synchronized (lifecycleMonitor) {
          if (run.cancelled || run.observation.get().state() == TranscodeJobState.STOPPED) {
            run.observation.set(
                observation(run.specification, TranscodeJobState.STOPPED, RenditionState.STOPPED));
            return run.observation.get();
          }
          withdrawPublication(run.specification.sessionId());
          run.observation.set(
              observation(run.specification, TranscodeJobState.FAILED, RenditionState.FAILED));
          run.cleanupComplete = true;
          return run.observation.get();
        }
      }
    } catch (RuntimeException cleanupFailure) {
      throw cleanupPending("Failed transcode job cleanup is pending", cleanupFailure);
    }
  }

  public TranscodeJobObservation stop(TranscodeJobRef jobRef) {
    var run = runs.get(jobRef);
    if (run == null) {
      return absent(jobRef);
    }
    synchronized (lifecycleMonitor) {
      if (run.observation.get().state() == TranscodeJobState.STOPPED && run.cleanupComplete) {
        return run.observation.get();
      }
      run.cancelled = true;
    }
    try {
      processManager.stopJob(jobRef);
      run.startup().handle((_, _) -> null).join();
      processManager.stopJob(jobRef);
      if (processManager.isRunning(jobRef)) {
        throw new IllegalStateException("Exact transcode processes remain active");
      }
      synchronized (run.monitor) {
        if (run.observation.get().state() == TranscodeJobState.STOPPED && run.cleanupComplete) {
          return run.observation.get();
        }
        synchronized (lifecycleMonitor) {
          var current =
              highestGenerationByJobId.getOrDefault(jobRef.jobId(), 0L) == jobRef.generation();
          if (current && publications.containsKey(run.specification.sessionId())) {
            withdrawPublication(run.specification.sessionId());
          } else if (!isPublished(run) && !run.cleanupComplete && run.generation.get() != null) {
            segmentStorage.discard(run.generation.get());
          }
          run.observation.set(
              observation(run.specification, TranscodeJobState.STOPPED, RenditionState.STOPPED));
          run.cleanupComplete = true;
          if (current) {
            sessionOwnerBySessionId.remove(run.specification.sessionId(), jobRef.jobId());
          }
          return run.observation.get();
        }
      }
    } catch (RuntimeException exception) {
      throw cleanupPending("Transcode job cleanup is pending", exception);
    }
  }

  public boolean releaseObservation(TranscodeJobRef jobRef) {
    var run = runs.get(jobRef);
    if (run == null) {
      return true;
    }
    synchronized (lifecycleMonitor) {
      if (!run.cleanupComplete || isPublished(run) || holdsFallbackStopAuthority(run)) {
        return false;
      }
      if (!processManager.releaseJobObservation(jobRef)) {
        return false;
      }
      runs.remove(jobRef, run);
      if (highestGenerationByJobId.getOrDefault(jobRef.jobId(), 0L) == jobRef.generation()) {
        sessionOwnerBySessionId.remove(run.specification.sessionId(), jobRef.jobId());
      }
    }
    return true;
  }

  public void shutdown() {
    List<JobRun> snapshot;
    synchronized (lifecycleMonitor) {
      shuttingDown = true;
      snapshot = List.copyOf(runs.values());
      snapshot.forEach(run -> run.cancelled = true);
    }
    try {
      processManager.forceStopAll();
      snapshot.forEach(run -> run.startup().handle((_, _) -> null).join());
      processManager.forceStopAll();
      snapshot.forEach(
          run -> {
            synchronized (run.monitor) {
              if (isPublished(run)) {
                withdrawPublication(run.specification.sessionId());
              } else if (!run.cleanupComplete && run.generation.get() != null) {
                segmentStorage.discard(run.generation.get());
              }
              run.observation.set(
                  observation(
                      run.specification, TranscodeJobState.STOPPED, RenditionState.STOPPED));
              run.cleanupComplete = true;
            }
          });
      sessionOwnerBySessionId.clear();
      segmentStorage.shutdown();
    } catch (RuntimeException exception) {
      throw cleanupPending("Transcode engine shutdown cleanup is pending", exception);
    }
  }

  private static void validateSpecification(TranscodeJobSpec specification) {
    if (specification.decision().subtitleDecision().mode() != SubtitleMode.EXCLUDE) {
      throw new TranscodeEngineException(
          TranscodeEngineException.Reason.INVALID_SPECIFICATION,
          "Local transcoding does not support the requested subtitle mode");
    }
  }

  private static TranscodeJobObservation absent(TranscodeJobRef jobRef) {
    return new TranscodeJobObservation(jobRef, TranscodeJobState.ABSENT, List.of());
  }

  private Admission admit(TranscodeJobSpec specification, Path resolvedSource) {
    synchronized (lifecycleMonitor) {
      if (shuttingDown) {
        throw new TranscodeEngineException(
            TranscodeEngineException.Reason.SHUTTING_DOWN, "Transcode engine is shutting down");
      }
      var existing = runs.get(specification.jobRef());
      if (existing != null) {
        if (!existing.matches(specification, resolvedSource)) {
          throw new TranscodeEngineException(
              TranscodeEngineException.Reason.JOB_CONFLICT,
              "Transcode job content conflicts with its exact identity");
        }
        return new Admission(existing, false);
      }
      var jobRef = specification.jobRef();
      var boundSessionId = sessionIdByJobId.get(jobRef.jobId());
      if (boundSessionId != null && !boundSessionId.equals(specification.sessionId())) {
        throw new TranscodeEngineException(
            TranscodeEngineException.Reason.JOB_CONFLICT,
            "Transcode job identity belongs to a different session");
      }
      var sessionOwner = sessionOwnerBySessionId.get(specification.sessionId());
      if (sessionOwner != null && !sessionOwner.equals(jobRef.jobId())) {
        throw new TranscodeEngineException(
            TranscodeEngineException.Reason.SESSION_CONFLICT,
            "Transcode session is already owned by a different job");
      }
      var highestGeneration = highestGenerationByJobId.getOrDefault(jobRef.jobId(), 0L);
      if (jobRef.generation() <= highestGeneration) {
        throw new TranscodeEngineException(
            TranscodeEngineException.Reason.STALE_GENERATION, "Transcode job generation is stale");
      }
      var run = new JobRun(specification, resolvedSource);
      var superseded =
          runs.values().stream()
              .filter(candidate -> candidate.jobId().equals(jobRef.jobId()))
              .filter(candidate -> candidate.generation() < jobRef.generation())
              .toList();
      superseded.forEach(candidate -> candidate.cancelled = true);
      highestGenerationByJobId.put(jobRef.jobId(), jobRef.generation());
      sessionIdByJobId.put(jobRef.jobId(), specification.sessionId());
      sessionOwnerBySessionId.put(specification.sessionId(), jobRef.jobId());
      runs.put(jobRef, run);
      return new Admission(run, true, superseded);
    }
  }

  private void fenceSuperseded(List<JobRun> superseded) {
    try {
      for (var run : superseded) {
        var jobRef = run.specification.jobRef();
        processManager.stopJob(jobRef);
        run.startup().handle((_, _) -> null).join();
        processManager.stopJob(jobRef);
        if (processManager.isRunning(jobRef)) {
          throw new TranscodeException("Superseded transcode process is still running");
        }
        synchronized (run.monitor) {
          if (!isPublished(run) && !run.cleanupComplete && run.generation.get() != null) {
            segmentStorage.discard(run.generation.get());
          }
          run.observation.set(
              observation(run.specification, TranscodeJobState.STOPPED, RenditionState.STOPPED));
          run.cleanupComplete = true;
        }
      }
    } catch (RuntimeException exception) {
      throw cleanupPending("Superseded transcode cleanup is pending", exception);
    }
  }

  private void assertAdmitted(JobRun run) {
    var jobRef = run.specification.jobRef();
    if (run.cancelled
        || highestGenerationByJobId.getOrDefault(jobRef.jobId(), 0L) > jobRef.generation()) {
      throw new TranscodeEngineException(
          TranscodeEngineException.Reason.STALE_GENERATION,
          "Transcode job generation was superseded");
    }
  }

  private RuntimeException compensate(
      JobRun run, SegmentGeneration generation, RuntimeException failure) {
    var startupFailure =
        failure instanceof TranscodeEngineException
            ? failure
            : new TranscodeEngineException(
                TranscodeEngineException.Reason.STARTUP_FAILED, failure.getMessage(), failure);
    try {
      processManager.stopJob(run.specification.jobRef());
      if (generation != null) {
        segmentStorage.discard(generation);
      }
      run.cleanupComplete = true;
    } catch (RuntimeException cleanupFailure) {
      var pending = cleanupPending("Transcode startup cleanup is pending", cleanupFailure);
      pending.addSuppressed(startupFailure);
      return pending;
    }
    return startupFailure;
  }

  private static TranscodeJobObservation await(CompletableFuture<TranscodeJobObservation> startup) {
    try {
      return startup.join();
    } catch (CompletionException exception) {
      if (exception.getCause() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw exception;
    }
  }

  private boolean isPublished(JobRun run) {
    return run.specification.jobRef().equals(publications.get(run.specification.sessionId()));
  }

  private boolean holdsFallbackStopAuthority(JobRun run) {
    var jobRef = run.specification.jobRef();
    var sessionId = run.specification.sessionId();
    return publications.containsKey(sessionId)
        && highestGenerationByJobId.getOrDefault(jobRef.jobId(), 0L) == jobRef.generation()
        && jobRef.jobId().equals(sessionOwnerBySessionId.get(sessionId));
  }

  private void withdrawPublication(UUID sessionId) {
    var jobRef = publications.get(sessionId);
    if (jobRef == null) {
      return;
    }
    segmentStorage.withdraw(sessionId, jobRef);
    publications.remove(sessionId, jobRef);
  }

  private void startRendition(
      TranscodeJobSpec specification,
      Path resolvedSource,
      SegmentGeneration generation,
      RenditionSpec rendition) {
    var outputDirectory = outputDirectory(specification, generation, rendition);
    var request = request(specification, resolvedSource, rendition);
    var job =
        RenditionJob.builder()
            .request(request)
            .videoEncoder(resolveEncoder(specification))
            .outputDir(outputDirectory)
            .build();
    var key = new FfmpegProcessKey(specification.jobRef(), rendition.label());
    processManager.startProcess(key, commandBuilder.buildCommand(job), outputDirectory);
  }

  private static RenditionRequest request(
      TranscodeJobSpec specification, Path resolvedSource, RenditionSpec rendition) {
    var execution = specification.execution();
    return RenditionRequest.builder()
        .sessionId(specification.sessionId())
        .sourcePath(resolvedSource)
        .seekPosition(execution.seekPosition())
        .segmentDuration(execution.segmentDuration())
        .framerate(execution.framerate())
        .transcodeDecision(specification.decision())
        .width(rendition.width())
        .height(rendition.height())
        .bitrate(rendition.videoBitrate())
        .variantLabel(rendition.label())
        .startNumber(execution.startNumber())
        .build();
  }

  private Path outputDirectory(
      TranscodeJobSpec specification, SegmentGeneration generation, RenditionSpec rendition) {
    if (specification.renditions().size() == 1
        && RenditionRequest.DEFAULT_VARIANT.equals(rendition.label())) {
      return generation.outputDirectory();
    }
    return segmentStorage.prepareRenditionDirectory(generation, rendition.label());
  }

  private String resolveEncoder(TranscodeJobSpec specification) {
    var mode = specification.decision().transcodeMode();
    if (mode == TranscodeMode.REMUX || mode == TranscodeMode.AUDIO_TRANSCODE) {
      return "copy";
    }
    return capabilityService.resolveEncoder(specification.decision().videoCodecFamily());
  }

  private void awaitReadiness(JobRun run, SegmentGeneration generation) {
    var specification = run.specification;
    var startedAt = System.nanoTime();
    var startupTimeoutNanos = specification.execution().startupTimeout().toNanos();
    while (System.nanoTime() - startedAt < startupTimeoutNanos) {
      assertAdmitted(run);
      assertProcessesHealthy(specification, generation);
      if (allRenditionsReady(specification, generation)) {
        return;
      }
      try {
        Thread.sleep(READINESS_POLL_INTERVAL);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new TranscodeException("Transcode startup interrupted", exception);
      }
    }
    assertProcessesHealthy(specification, generation);
    if (!allRenditionsReady(specification, generation)) {
      throw new TranscodeException("Transcode startup timed out");
    }
  }

  private void assertProcessesHealthy(
      TranscodeJobSpec specification, SegmentGeneration generation) {
    for (var rendition : specification.renditions()) {
      healthyState(specification, generation, rendition);
    }
  }

  private RenditionState healthyState(
      TranscodeJobSpec specification, SegmentGeneration generation, RenditionSpec rendition) {
    var key = new FfmpegProcessKey(specification.jobRef(), rendition.label());
    return switch (processManager.observe(key).state()) {
      case RUNNING -> RenditionState.RUNNING;
      case COMPLETED -> {
        if (playlistHasEndMarker(specification, generation, rendition)) {
          yield RenditionState.COMPLETED;
        }
        throw startupFailure();
      }
      case FAILED, STOPPED, ABSENT -> throw startupFailure();
    };
  }

  private static TranscodeEngineException startupFailure() {
    return new TranscodeEngineException(
        TranscodeEngineException.Reason.STARTUP_FAILED,
        "Transcode rendition failed during startup");
  }

  private static TranscodeEngineException cleanupPending(String message, RuntimeException cause) {
    return new TranscodeEngineException(
        TranscodeEngineException.Reason.CLEANUP_PENDING, message, cause);
  }

  private boolean playlistHasEndMarker(
      TranscodeJobSpec specification, SegmentGeneration generation, RenditionSpec rendition) {
    return segmentStorage
        .readStagedArtifact(generation, artifactName(specification, rendition, "stream.m3u8"))
        .map(contents -> new String(contents, StandardCharsets.UTF_8))
        .stream()
        .flatMap(String::lines)
        .anyMatch("#EXT-X-ENDLIST"::equals);
  }

  private boolean allRenditionsReady(TranscodeJobSpec specification, SegmentGeneration generation) {
    return specification.renditions().stream()
        .allMatch(rendition -> renditionReady(specification, generation, rendition));
  }

  private boolean renditionReady(
      TranscodeJobSpec specification, SegmentGeneration generation, RenditionSpec rendition) {
    var firstSegment =
        "segment"
            + specification.execution().startNumber()
            + specification.decision().containerFormat().segmentExtension();
    if (!nonEmpty(generation, artifactName(specification, rendition, "stream.m3u8"))
        || !nonEmpty(generation, artifactName(specification, rendition, firstSegment))) {
      return false;
    }
    return specification.decision().containerFormat().segmentExtension().equals(".ts")
        || nonEmpty(generation, artifactName(specification, rendition, "init.mp4"));
  }

  private static String artifactName(
      TranscodeJobSpec specification, RenditionSpec rendition, String filename) {
    if (specification.renditions().size() == 1
        && RenditionRequest.DEFAULT_VARIANT.equals(rendition.label())) {
      return filename;
    }
    return rendition.label() + "/" + filename;
  }

  private boolean nonEmpty(SegmentGeneration generation, String relativeName) {
    return segmentStorage.isStagedArtifactNonEmpty(generation, relativeName);
  }

  private static TranscodeJobObservation observation(
      TranscodeJobSpec specification, TranscodeJobState jobState, RenditionState renditionState) {
    var renditions =
        specification.renditions().stream()
            .map(rendition -> new RenditionObservation(rendition.label(), renditionState))
            .toList();
    return new TranscodeJobObservation(specification.jobRef(), jobState, List.copyOf(renditions));
  }

  private TranscodeJobObservation healthyObservation(
      TranscodeJobSpec specification, SegmentGeneration generation) {
    var renditions =
        specification.renditions().stream()
            .map(
                rendition ->
                    new RenditionObservation(
                        rendition.label(), healthyState(specification, generation, rendition)))
            .toList();
    var jobState =
        renditions.stream().allMatch(rendition -> rendition.state() == RenditionState.COMPLETED)
            ? TranscodeJobState.COMPLETED
            : TranscodeJobState.RUNNING;
    return new TranscodeJobObservation(specification.jobRef(), jobState, renditions);
  }

  private record Admission(JobRun run, boolean owner, List<JobRun> superseded) {

    private Admission(JobRun run, boolean owner) {
      this(run, owner, List.of());
    }
  }

  private static final class JobRun {

    private final TranscodeJobSpec specification;
    private final Path resolvedSource;
    private final CompletableFuture<TranscodeJobObservation> startup = new CompletableFuture<>();
    private final Object monitor = new Object();
    private final AtomicReference<SegmentGeneration> generation = new AtomicReference<>();
    private final AtomicReference<TranscodeJobObservation> observation = new AtomicReference<>();
    private volatile boolean cancelled;
    private volatile boolean cleanupComplete;

    private JobRun(TranscodeJobSpec specification, Path resolvedSource) {
      this.specification = specification;
      this.resolvedSource = resolvedSource;
      observation.set(
          LocalTranscodeEngine.observation(
              specification, TranscodeJobState.ADMITTING, RenditionState.STARTING));
    }

    private CompletableFuture<TranscodeJobObservation> startup() {
      return startup;
    }

    private boolean matches(TranscodeJobSpec otherSpecification, Path otherSource) {
      return specification.equals(otherSpecification) && resolvedSource.equals(otherSource);
    }

    private UUID jobId() {
      return specification.jobRef().jobId();
    }

    private long generation() {
      return specification.jobRef().generation();
    }
  }
}
