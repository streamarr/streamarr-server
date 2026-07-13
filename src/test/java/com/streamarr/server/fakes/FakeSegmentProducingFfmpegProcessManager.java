package com.streamarr.server.fakes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class FakeSegmentProducingFfmpegProcessManager extends FakeFfmpegProcessManager {

  private final String segmentName;
  private final byte[] segmentData;

  public FakeSegmentProducingFfmpegProcessManager(String segmentName, byte[] segmentData) {
    this.segmentName = segmentName;
    this.segmentData = segmentData.clone();
  }

  @Override
  public Process startProcess(
      UUID sessionId, String renditionName, List<String> command, Path workingDirectory) {
    var process = super.startProcess(sessionId, renditionName, command, workingDirectory);
    try {
      Files.write(workingDirectory.resolve(segmentName), segmentData);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return process;
  }
}
