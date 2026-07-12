package com.streamarr.transcode.engine.ffmpeg;

import java.util.Objects;
import java.util.OptionalInt;

public record FfmpegProcessObservation(FfmpegProcessState state, OptionalInt exitCode) {

  public FfmpegProcessObservation {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(exitCode, "exitCode must not be null");
  }

  public static FfmpegProcessObservation withoutExitCode(FfmpegProcessState state) {
    return new FfmpegProcessObservation(state, OptionalInt.empty());
  }

  public static FfmpegProcessObservation withExitCode(FfmpegProcessState state, int exitCode) {
    return new FfmpegProcessObservation(state, OptionalInt.of(exitCode));
  }
}
