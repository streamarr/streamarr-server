package com.streamarr.server.services.streaming.ffmpeg;

import com.streamarr.server.domain.streaming.ProducerEnd;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FfmpegProcessManager {

  Process startProcess(UUID sessionId, String variantLabel, List<String> command, Path workingDir);

  /** Attempt-tagged start; managers that retain attempt-scoped exit evidence override this. */
  default Process startProcess(
      UUID sessionId, String variantLabel, UUID attemptId, List<String> command, Path workingDir) {
    return startProcess(sessionId, variantLabel, command, workingDir);
  }

  void stopProcess(UUID sessionId);

  void stopProcess(UUID sessionId, String variantLabel);

  boolean isRunning(UUID sessionId);

  boolean isRunning(UUID sessionId, String variantLabel);

  /** Consume-once exit evidence for the given attempt; empty when none was retained. */
  default Optional<ProducerEnd> consumeExit(
      UUID sessionId, String variantLabel, UUID expectedAttemptId) {
    return Optional.empty();
  }
}
