package com.streamarr.server.services.streaming.ffmpeg;

import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeJob;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import java.nio.file.Path;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class FfmpegTranscodeEngine {

  private final FfmpegCommandBuilder commandBuilder;
  private final FfmpegProcessManager processManager;
  private final TranscodeCapabilityService capabilityService;

  public TranscodeHandle start(TranscodeRequest request, Path outputDirectory) {
    var job =
        TranscodeJob.builder()
            .request(request)
            .videoEncoder(resolveEncoder(request))
            .outputDir(outputDirectory)
            .build();
    var command = commandBuilder.buildCommand(job);

    log.debug("FFmpeg command for session {}: {}", request.sessionId(), String.join(" ", command));

    var process =
        processManager.startProcess(
            request.sessionId(), request.variantLabel(), command, job.outputDir());

    log.info(
        "Started transcode for session {} variant {} (encoder: {}, PID: {})",
        request.sessionId(),
        request.variantLabel(),
        job.videoEncoder(),
        process.pid());

    return new TranscodeHandle(process.pid(), TranscodeStatus.ACTIVE, request.startNumber());
  }

  public void stop(UUID sessionId) {
    processManager.stopProcess(sessionId);
  }

  public void stop(UUID sessionId, String variantLabel) {
    processManager.stopProcess(sessionId, variantLabel);
  }

  public boolean isRunning(UUID sessionId) {
    return processManager.isRunning(sessionId);
  }

  public boolean isRunning(UUID sessionId, String variantLabel) {
    return processManager.isRunning(sessionId, variantLabel);
  }

  public boolean isHealthy() {
    return capabilityService.isFfmpegAvailable();
  }

  private String resolveEncoder(TranscodeRequest request) {
    var mode = request.transcodeDecision().transcodeMode();
    if (mode == TranscodeMode.REMUX || mode == TranscodeMode.AUDIO_TRANSCODE) {
      return "copy";
    }

    return capabilityService.resolveEncoder(request.transcodeDecision().videoCodecFamily());
  }
}
