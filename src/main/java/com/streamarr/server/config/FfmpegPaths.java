package com.streamarr.server.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.NonNull;

public record FfmpegPaths(@NonNull String ffmpeg, @NonNull String ffprobe) {

  private static final List<String> FFMPEG_SEARCH_PATHS =
      List.of(
          "/usr/bin/ffmpeg",
          "/usr/local/bin/ffmpeg",
          "/opt/homebrew/bin/ffmpeg",
          "C:\\ffmpeg\\bin\\ffmpeg.exe");

  private static final List<String> FFPROBE_SEARCH_PATHS =
      List.of(
          "/usr/bin/ffprobe",
          "/usr/local/bin/ffprobe",
          "/opt/homebrew/bin/ffprobe",
          "C:\\ffmpeg\\bin\\ffprobe.exe");

  public static FfmpegPaths resolve(String ffmpegOverride, String ffprobeOverride) {
    return new FfmpegPaths(
        resolveExecutable(ffmpegOverride, FFMPEG_SEARCH_PATHS),
        resolveExecutable(ffprobeOverride, FFPROBE_SEARCH_PATHS));
  }

  private static String resolveExecutable(String override, List<String> searchPaths) {
    if (override != null && !override.isBlank()) {
      return override;
    }

    return searchPaths.stream()
        .filter(path -> Files.isExecutable(Path.of(path)))
        .findFirst()
        .orElse(searchPaths.getFirst());
  }
}
