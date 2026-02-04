package com.streamarr.server.services.streaming.ffmpeg;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public interface FfmpegProcessManager {

  Process startProcess(UUID sessionId, String variantLabel, List<String> command, Path workingDir);

  void stopProcess(UUID sessionId);

  boolean isRunning(UUID sessionId);

  boolean isRunning(UUID sessionId, String variantLabel);
}
