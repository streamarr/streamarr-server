package com.streamarr.server.config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
    return resolve(ffmpegOverride, ffprobeOverride, System.getenv("PATH"));
  }

  static FfmpegPaths resolve(String ffmpegOverride, String ffprobeOverride, String systemPath) {
    return new FfmpegPaths(
        resolveExecutable(ffmpegOverride, FFMPEG_SEARCH_PATHS, systemPath),
        resolveExecutable(ffprobeOverride, FFPROBE_SEARCH_PATHS, systemPath));
  }

  private static String resolveExecutable(
      String override, List<String> searchPaths, String systemPath) {
    if (override != null && !override.isBlank()) {
      return override;
    }

    var executableName = Path.of(searchPaths.getFirst()).getFileName().toString();

    return resolveFromSystemPath(executableName, systemPath)
        .or(
            () ->
                searchPaths.stream().filter(path -> Files.isExecutable(Path.of(path))).findFirst())
        .orElse(searchPaths.getFirst());
  }

  private static Optional<String> resolveFromSystemPath(String executableName, String systemPath) {
    if (systemPath == null || systemPath.isBlank()) {
      return Optional.empty();
    }

    return Arrays.stream(systemPath.split(File.pathSeparator))
        .map(dir -> Path.of(dir, executableName))
        .filter(Files::isExecutable)
        .map(Path::toString)
        .findFirst();
  }
}
