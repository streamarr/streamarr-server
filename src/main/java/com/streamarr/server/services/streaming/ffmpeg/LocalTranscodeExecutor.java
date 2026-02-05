package com.streamarr.server.services.streaming.ffmpeg;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeJob;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import java.nio.file.Path;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class LocalTranscodeExecutor implements TranscodeExecutor {

  private final FfmpegCommandBuilder commandBuilder;
  private final FfmpegProcessManager processManager;
  private final LocalSegmentStore segmentStore;
  private final TranscodeCapabilityService capabilityService;

  @Override
  public TranscodeHandle start(TranscodeRequest request) {
    var job = resolveJob(request);
    var command = commandBuilder.buildCommand(job);

    var process =
        processManager.startProcess(
            request.sessionId(), request.variantLabel(), command, job.outputDir());

    log.info(
        "Started transcode for session {} variant {} (encoder: {}, PID: {})",
        request.sessionId(),
        request.variantLabel(),
        job.videoEncoder(),
        process.pid());

    return new TranscodeHandle(process.pid(), TranscodeStatus.ACTIVE);
  }

  @Override
  public void stop(UUID sessionId) {
    processManager.stopProcess(sessionId);
    log.info("Stopped transcode for session {}", sessionId);
  }

  @Override
  public boolean isRunning(UUID sessionId) {
    return processManager.isRunning(sessionId);
  }

  @Override
  public boolean isRunning(UUID sessionId, String variantLabel) {
    return processManager.isRunning(sessionId, variantLabel);
  }

  @Override
  public boolean isHealthy() {
    return capabilityService.isFfmpegAvailable();
  }

  private TranscodeJob resolveJob(TranscodeRequest request) {
    var videoEncoder = resolveEncoder(request);
    var outputDir = resolveOutputDir(request);

    return TranscodeJob.builder()
        .request(request)
        .videoEncoder(videoEncoder)
        .outputDir(outputDir)
        .build();
  }

  private Path resolveOutputDir(TranscodeRequest request) {
    if (StreamSession.defaultVariant().equals(request.variantLabel())) {
      return segmentStore.getOutputDirectory(request.sessionId());
    }
    return segmentStore.getOutputDirectory(request.sessionId(), request.variantLabel());
  }

  private String resolveEncoder(TranscodeRequest request) {
    var mode = request.transcodeDecision().transcodeMode();
    if (mode == TranscodeMode.REMUX || mode == TranscodeMode.PARTIAL_TRANSCODE) {
      return "copy";
    }
    return capabilityService.resolveEncoder(request.transcodeDecision().videoCodecFamily());
  }
}
