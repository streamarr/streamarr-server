package com.streamarr.server.services.streaming.ffmpeg;

import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeJob;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class LocalTranscodeExecutor implements TranscodeExecutor {

  private final FfmpegCommandBuilder commandBuilder;
  private final FfmpegProcessManager processManager;
  private final SegmentStore segmentStore;
  private final TranscodeCapabilityService capabilityService;

  @Override
  public TranscodeHandle start(TranscodeRequest request) {
    var job = resolveJob(request);
    var command = commandBuilder.buildCommand(job);

    var process = processManager.startProcess(request.sessionId(), command, job.outputDir());

    var pid = process != null ? process.pid() : -1L;
    log.info(
        "Started transcode for session {} (encoder: {}, PID: {})",
        request.sessionId(),
        job.videoEncoder(),
        pid);

    return new TranscodeHandle(pid, TranscodeStatus.ACTIVE);
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
  public boolean isHealthy() {
    return capabilityService.isFfmpegAvailable();
  }

  private TranscodeJob resolveJob(TranscodeRequest request) {
    var videoEncoder = resolveEncoder(request);
    var outputDir = segmentStore.getOutputDirectory(request.sessionId());

    return TranscodeJob.builder()
        .request(request)
        .videoEncoder(videoEncoder)
        .outputDir(outputDir)
        .build();
  }

  private String resolveEncoder(TranscodeRequest request) {
    var mode = request.transcodeDecision().transcodeMode();
    if (mode == TranscodeMode.REMUX || mode == TranscodeMode.PARTIAL_TRANSCODE) {
      return "copy";
    }
    return capabilityService.resolveEncoder(request.transcodeDecision().videoCodecFamily());
  }
}
