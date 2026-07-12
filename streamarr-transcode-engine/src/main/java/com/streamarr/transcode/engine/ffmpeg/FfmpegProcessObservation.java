package com.streamarr.transcode.engine.ffmpeg;

import java.util.OptionalInt;

public record FfmpegProcessObservation(FfmpegProcessState state, OptionalInt exitCode) {

  public FfmpegProcessObservation {
    if (state == null || exitCode == null) {
      throw new IllegalArgumentException("Process observation values are required");
    }
    var terminal =
        switch (state) {
          case COMPLETED, FAILED, STOPPED -> true;
          case RUNNING, ABSENT -> false;
        };
    if (terminal != exitCode.isPresent()) {
      throw new IllegalArgumentException("Process state and exit code contradict");
    }
  }

  public static FfmpegProcessObservation withoutExitCode(FfmpegProcessState state) {
    return new FfmpegProcessObservation(state, OptionalInt.empty());
  }

  public static FfmpegProcessObservation withExitCode(FfmpegProcessState state, int exitCode) {
    return new FfmpegProcessObservation(state, OptionalInt.of(exitCode));
  }
}
