package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalInt;

public record SubtitleDecision(
    SubtitleMode mode, Optional<String> codec, OptionalInt streamIndex, Optional<String> language) {

  public static SubtitleDecision exclude() {
    return new SubtitleDecision(
        SubtitleMode.EXCLUDE, Optional.empty(), OptionalInt.empty(), Optional.empty());
  }
}
