package com.streamarr.server.services.streaming.ffmpeg;

import com.streamarr.server.domain.streaming.ProducerEnd;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class LocalTranscodeExecutor implements TranscodeExecutor {

  private final FfmpegTranscodeEngine engine;
  private final LocalSegmentStore segmentStore;

  @Override
  public TranscodeHandle start(TranscodeRequest request) {
    return engine.start(request, resolveOutputDir(request));
  }

  @Override
  public TranscodeHandle start(TranscodeRequest request, ExecutionTargetId target) {
    return start(request);
  }

  @Override
  public void stop(UUID sessionId) {
    engine.stop(sessionId);
    log.info("Stopped transcode for session {}", sessionId);
  }

  @Override
  public void stopVariant(UUID sessionId, String variantLabel) {
    engine.stop(sessionId, variantLabel);
    log.info("Stopped transcode for session {} variant {}", sessionId, variantLabel);
  }

  @Override
  public boolean isRunning(UUID sessionId) {
    return engine.isRunning(sessionId);
  }

  @Override
  public boolean isRunning(UUID sessionId, String variantLabel) {
    return engine.isRunning(sessionId, variantLabel);
  }

  @Override
  public boolean isHealthy() {
    return engine.isHealthy();
  }

  @Override
  public int availableSlots() {
    return UNBOUNDED_SLOTS;
  }

  @Override
  public Set<ExecutionTargetId> executionTargets() {
    return Set.of(ExecutionTargetId.LOCAL);
  }

  @Override
  public Optional<ProducerEnd> deathEvidence(
      UUID sessionId, String variantLabel, UUID expectedAttemptId) {
    return engine.consumeExit(sessionId, variantLabel, expectedAttemptId);
  }

  private Path resolveOutputDir(TranscodeRequest request) {
    if (StreamSession.defaultVariant().equals(request.variantLabel())) {
      return segmentStore.getOutputDirectory(request.sessionId());
    }

    return segmentStore.getOutputDirectory(request.sessionId(), request.variantLabel());
  }
}
