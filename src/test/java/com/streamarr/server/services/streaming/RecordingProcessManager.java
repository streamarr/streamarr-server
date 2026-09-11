package com.streamarr.server.services.streaming;

import com.streamarr.server.services.streaming.ffmpeg.LocalFfmpegProcessManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class RecordingProcessManager extends LocalFfmpegProcessManager {

  private final Map<UUID, List<String>> lastCommands = new ConcurrentHashMap<>();
  private final Map<UUID, Process> lastProcesses = new ConcurrentHashMap<>();

  @Override
  public Process startProcess(
      UUID sessionId, String variantLabel, List<String> command, Path workingDir) {
    lastCommands.put(sessionId, List.copyOf(command));
    var process = super.startProcess(sessionId, variantLabel, command, workingDir);
    lastProcesses.put(sessionId, process);
    return process;
  }

  Optional<Process> lastProcessFor(UUID sessionId) {
    return Optional.ofNullable(lastProcesses.get(sessionId));
  }

  Optional<List<String>> lastCommandFor(UUID sessionId) {
    return Optional.ofNullable(lastCommands.get(sessionId));
  }
}
