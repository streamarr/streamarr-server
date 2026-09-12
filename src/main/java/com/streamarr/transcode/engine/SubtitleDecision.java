package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.OptionalInt;

public record SubtitleDecision(
    SubtitleMode mode,
    Optional<String> codec,
    OptionalInt streamIndex,
    Optional<String> language) {}
