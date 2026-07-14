package com.streamarr.server.fakes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class FakeSegmentProducingFfmpegProcessManager extends FakeFfmpegProcessManager {

  private final Map<String, byte[]> segments;

  public FakeSegmentProducingFfmpegProcessManager(String segmentName, byte[] segmentData) {
    this(Map.of(segmentName, segmentData));
  }

  public FakeSegmentProducingFfmpegProcessManager(Map<String, byte[]> segments) {
    this.segments =
        segments.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().clone()));
  }

  @Override
  public Process startProcess(
      UUID sessionId, String variantLabel, List<String> command, Path workingDirectory) {
    var process = super.startProcess(sessionId, variantLabel, command, workingDirectory);
    try {
      for (var segment : segments.entrySet()) {
        Files.write(workingDirectory.resolve(segment.getKey()), segment.getValue());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return process;
  }
}
