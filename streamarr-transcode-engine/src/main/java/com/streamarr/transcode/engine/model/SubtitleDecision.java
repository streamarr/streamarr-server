package com.streamarr.transcode.engine.model;

import java.util.Optional;
import java.util.OptionalInt;

public record SubtitleDecision(
    SubtitleMode mode, Optional<String> codec, OptionalInt streamIndex, Optional<String> language) {

  public SubtitleDecision {
    if (mode == null || codec == null || streamIndex == null || language == null) {
      throw new IllegalArgumentException("Subtitle decision values are required");
    }
    if (codec.filter(String::isBlank).isPresent()
        || language.filter(String::isBlank).isPresent()
        || streamIndex.stream().anyMatch(index -> index < 0)) {
      throw new IllegalArgumentException("Subtitle decision values are invalid");
    }
    if (mode == SubtitleMode.EXCLUDE
        && (codec.isPresent() || streamIndex.isPresent() || language.isPresent())) {
      throw new IllegalArgumentException("Excluded subtitles cannot carry stream values");
    }
  }

  public static SubtitleDecision exclude() {
    return new SubtitleDecision(
        SubtitleMode.EXCLUDE, Optional.empty(), OptionalInt.empty(), Optional.empty());
  }
}
