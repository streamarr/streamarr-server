package com.streamarr.server.services.streaming.ffmpeg;

import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.transcode.engine.error.TranscodeException;
import com.streamarr.transcode.engine.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessKey;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessManager;
import com.streamarr.transcode.engine.ffmpeg.TranscodeCapabilityService;
import com.streamarr.transcode.engine.model.RenditionJob;
import com.streamarr.transcode.engine.model.RenditionRequest;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import com.streamarr.transcode.engine.model.TranscodeMode;
import java.nio.file.Path;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class LocalTranscodeExecutor implements TranscodeExecutor {

  private static final long LEGACY_JOB_GENERATION = 1L;

  private final FfmpegCommandBuilder commandBuilder;
  private final FfmpegProcessManager processManager;
  private final LocalSegmentStore segmentStore;
  private final TranscodeCapabilityService capabilityService;

  @Override
  public TranscodeHandle start(RenditionRequest request) {
    var job = resolveJob(request);
    var command = commandBuilder.buildCommand(job);

    log.debug("FFmpeg command for session {}: {}", request.sessionId(), String.join(" ", command));

    var process =
        processManager.startProcess(
            processKey(request.sessionId(), request.variantLabel()), command, job.outputDir());

    log.info(
        "Started transcode for session {} variant {} (encoder: {}, PID: {})",
        request.sessionId(),
        request.variantLabel(),
        job.videoEncoder(),
        process.pid());

    return new TranscodeHandle(process.pid(), TranscodeStatus.ACTIVE, request.startNumber());
  }

  @Override
  public void stop(UUID sessionId) {
    var jobRef = jobRef(sessionId);
    processManager.stopJob(jobRef);
    if (!processManager.releaseJobObservation(jobRef)) {
      throw new TranscodeException("Transcode cleanup is still pending");
    }
    log.info("Stopped transcode for session {}", sessionId);
  }

  @Override
  public void forceStopAll() {
    processManager.forceStopAll();
  }

  @Override
  public boolean isRunning(UUID sessionId) {
    return processManager.isRunning(jobRef(sessionId));
  }

  @Override
  public boolean isRunning(UUID sessionId, String variantLabel) {
    return processManager.isRunning(processKey(sessionId, variantLabel));
  }

  @Override
  public boolean isHealthy() {
    return capabilityService.isFfmpegAvailable();
  }

  private RenditionJob resolveJob(RenditionRequest request) {
    var videoEncoder = resolveEncoder(request);
    var outputDir = resolveOutputDir(request);

    return RenditionJob.builder()
        .request(request)
        .videoEncoder(videoEncoder)
        .outputDir(outputDir)
        .build();
  }

  private Path resolveOutputDir(RenditionRequest request) {
    if (RenditionRequest.DEFAULT_VARIANT.equals(request.variantLabel())) {
      return segmentStore.getOutputDirectory(request.sessionId());
    }

    return segmentStore.getOutputDirectory(request.sessionId(), request.variantLabel());
  }

  private String resolveEncoder(RenditionRequest request) {
    var mode = request.transcodeDecision().transcodeMode();
    if (mode == TranscodeMode.REMUX || mode == TranscodeMode.AUDIO_TRANSCODE) {
      return "copy";
    }

    return capabilityService.resolveEncoder(request.transcodeDecision().videoCodecFamily());
  }

  private static FfmpegProcessKey processKey(UUID sessionId, String renditionLabel) {
    return new FfmpegProcessKey(jobRef(sessionId), renditionLabel);
  }

  private static TranscodeJobRef jobRef(UUID sessionId) {
    return new TranscodeJobRef(sessionId, LEGACY_JOB_GENERATION);
  }
}
